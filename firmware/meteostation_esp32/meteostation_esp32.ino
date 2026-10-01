/*
  Цифровая метеостанция: ESP32 + BME280 + OLED SSD1306 + приложение для Android

  Что делает:
  - каждые 5 с измеряет температуру, влажность и давление (режим Bosch «weather monitoring»);
  - вычисляет давление, приведённое к уровню моря, и точку росы; выводит всё на OLED;
  - берёт точное время из интернета (NTP) или из телефона (приложение);
  - каждые N минут (по умолчанию 10, по часам: :00, :10, :20...) пишет запись в журнал во флеш-памяти;
  - отдаёт данные приложению по Wi-Fi (HTTP API, JSON/CSV);
  - без домашней сети сама создаёт точку доступа «MeteoStation» (пароль meteo2026), адрес 192.168.4.1.

  Подключение (I2C): BME280 и OLED: VCC -> 3V3, GND -> GND, SDA -> GPIO21, SCL -> GPIO22.
  Плата в Arduino IDE: «ESP32 Dev Module», Partition Scheme: «Default 4MB with spiffs».
  Библиотеки: Adafruit BME280, Adafruit SSD1306, Adafruit GFX (ядро ESP32 3.x).

  HTTP API:
    GET  /api/now                 текущие значения (JSON)
    GET  /api/history?since=EPOCH записи журнала новее EPOCH (CSV: ts;t;h;p)
    GET  /api/log.csv             весь журнал (CSV для Excel)
    GET  /api/config              настройки (JSON)
    POST /api/config?alt=60&interval=10   высота датчика, м; интервал записи, мин
    POST /api/time?epoch=...      установить время (из телефона)
    POST /api/wifi?ssid=...&pass=...      сохранить домашнюю сеть и перезагрузиться
    POST /api/clear               очистить журнал (новая серия измерений)
*/

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <Wire.h>
#include <time.h>
#include <sys/time.h>
#include "esp_sntp.h"
#include <Adafruit_Sensor.h>
#include <Adafruit_BME280.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#define FW_VERSION "1.0"

// ---------- постоянные настройки ----------
const char *AP_SSID = "MeteoStation";
const char *AP_PASS = "meteo2026";        // не короче 8 символов
const char *HOSTNAME = "meteo";           // адрес в сети: meteo.local
const int SDA_PIN = 21, SCL_PIN = 22;
const char *LOG_PATH = "/log.csv";
const size_t LOG_MAX_BYTES = 1000000;     // ~1 МБ — более 200 суток при записи раз в 10 мин
const uint32_t MEASURE_PERIOD_MS = 5000;

// ---------- объекты ----------
Preferences prefs;
WebServer server(80);
Adafruit_BME280 bme;
Adafruit_SSD1306 display(128, 64, &Wire, -1);

// ---------- изменяемые настройки (хранятся в NVS) ----------
String wifiSsid, wifiPass;
float altitudeM = 60.0;                   // высота датчика над уровнем моря, м
uint32_t logIntervalMin = 10;             // интервал записи в журнал, мин

// ---------- состояние ----------
bool hasBME = false, isBME280 = false, hasOLED = false, fsOk = false, logFull = false;
volatile bool ntpSynced = false;
bool apActive = false;
float curT = NAN, curH = NAN, curP = NAN;
uint32_t lastMeasure = 0, lastWifiRetry = 0;
long lastLogSlot = -1;
uint32_t logCount = 0;

// ================= физика =================
// Приведение давления к уровню моря (стандартная атмосфера, ГОСТ 4401-81)
float seaLevel(float p) { return p / pow(1.0 - altitudeM / 44330.0, 5.255); }

// Точка росы по формуле Магнуса (коэффициенты Алдучова–Эскриджа)
float dewPoint(float t, float rh) {
  if (isnan(t) || isnan(rh) || rh <= 0) return NAN;
  const float a = 17.625, b = 243.04;
  float g = log(rh / 100.0) + a * t / (b + t);
  return b * g / (a - g);
}

// ================= время =================
bool timeValid() { return time(nullptr) > 1700000000; }   // позже ноября 2023

void onNtpSync(struct timeval *tv) { ntpSynced = true; }

// ================= измерение =================
void measure() {
  if (!hasBME) return;
  bme.takeForcedMeasurement();            // один замер по запросу — датчик не греется
  curT = bme.readTemperature();
  curH = isBME280 ? bme.readHumidity() : NAN;
  curP = bme.readPressure() / 100.0;      // Па -> гПа
}

// ================= журнал =================
void countLog() {
  logCount = 0;
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return;
  uint8_t buf[512];
  size_t n;
  while ((n = f.read(buf, sizeof(buf))) > 0)
    for (size_t i = 0; i < n; i++) if (buf[i] == '\n') logCount++;
  if (logCount > 0) logCount--;           // без строки заголовка
  logFull = f.size() >= LOG_MAX_BYTES;
  f.close();
}

void appendLog(time_t ts) {
  if (!fsOk || !hasBME || isnan(curP)) return;
  if (!LittleFS.exists(LOG_PATH)) {
    File h = LittleFS.open(LOG_PATH, "w");
    if (h) { h.print("ts;t_C;rh_pct;p_hPa\n"); h.close(); }
  }
  File f = LittleFS.open(LOG_PATH, "a");
  if (!f) return;
  if (f.size() >= LOG_MAX_BYTES) { logFull = true; f.close(); return; }
  char line[64];
  snprintf(line, sizeof(line), "%ld;%.2f;%.1f;%.2f\n", (long)ts, curT, isnan(curH) ? -1.0 : curH, curP);
  f.print(line);
  f.close();
  logCount++;
}

// запись строго по часам: :00, :10, :20 ... — удобно сравнивать со сроками метеостанции
void logIfDue() {
  if (!timeValid()) return;
  time_t now = time(nullptr);
  long slot = now / (logIntervalMin * 60);
  if (slot != lastLogSlot) {
    if (lastLogSlot != -1) appendLog(slot * logIntervalMin * 60);
    lastLogSlot = slot;
  }
}

// ================= дисплей =================
String ipText() {
  if (WiFi.status() == WL_CONNECTED) return WiFi.localIP().toString();
  if (apActive) return "AP " + WiFi.softAPIP().toString();
  return "no Wi-Fi";
}

void showScreen() {
  if (!hasOLED) return;
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  if (!hasBME) {
    display.setTextSize(1); display.setCursor(0, 0);
    display.print(F("BME280 not found")); display.display(); return;
  }
  float p0 = seaLevel(curP);
  display.setTextSize(2); display.setCursor(0, 0);
  display.print(curT, 1); display.print((char)247); display.print('C');
  display.setTextSize(1);
  display.setCursor(0, 18); display.print(F("RH ")); display.print(curH, 0); display.print(F("%  Td "));
  display.print(dewPoint(curT, curH), 1);
  display.setCursor(0, 28); display.print(F("P0 ")); display.print(p0, 1); display.print(F(" hPa"));
  display.setCursor(0, 38); display.print(F("   ")); display.print(p0 * 0.750062, 1); display.print(F(" mmHg"));
  display.setCursor(0, 48); display.print(timeValid() ? F("log ") : F("NO TIME ")); display.print(logCount);
  if (logFull) display.print(F(" FULL"));
  display.setCursor(0, 56); display.print(ipText());
  display.display();
}

// ================= Wi-Fi =================
void startAP() {
  WiFi.mode(wifiSsid.length() ? WIFI_AP_STA : WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  apActive = true;
}

void startWiFi() {
  WiFi.setHostname(HOSTNAME);
  if (wifiSsid.length()) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) delay(250);
  }
  if (WiFi.status() != WL_CONNECTED) startAP();
  WiFi.setSleep(true);                     // экономия энергии и меньше нагрев
  MDNS.begin(HOSTNAME);
  MDNS.setInstanceName("meteostation");
  MDNS.addService("http", "tcp", 80);
}

void maintainWiFi() {
  if (!wifiSsid.length()) return;
  bool sta = WiFi.status() == WL_CONNECTED;
  if (sta && apActive && WiFi.softAPgetStationNum() == 0) {   // сеть появилась — точку доступа выключаем
    WiFi.softAPdisconnect(true); WiFi.mode(WIFI_STA); apActive = false;
  }
  if (!sta && millis() - lastWifiRetry > 60000) {
    lastWifiRetry = millis();
    if (!apActive) startAP();
    if (WiFi.softAPgetStationNum() == 0) { WiFi.disconnect(); WiFi.begin(wifiSsid.c_str(), wifiPass.c_str()); }
  }
}

// ================= HTTP =================
void sendJson(const String &s, int code = 200) {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(code, "application/json", s);
}

String num(float v, int d) { return isnan(v) ? String("null") : String(v, d); }

String jsonEscape(const String &s) {
  String r;
  for (char c : s) { if (c == '"' || c == '\\') r += '\\'; r += c; }
  return r;
}

void handleNow() {
  String j = "{";
  j += "\"ok\":" + String(hasBME ? "true" : "false");
  j += ",\"chip\":\"" + String(!hasBME ? "none" : (isBME280 ? "BME280" : "BMP280")) + "\"";
  j += ",\"ts\":" + String((long)(timeValid() ? time(nullptr) : 0));
  j += ",\"timeValid\":" + String(timeValid() ? "true" : "false");
  j += ",\"ntp\":" + String(ntpSynced ? "true" : "false");
  j += ",\"t\":" + num(curT, 2);
  j += ",\"h\":" + num(curH, 1);
  j += ",\"p\":" + num(curP, 2);
  j += ",\"p0\":" + num(hasBME ? seaLevel(curP) : NAN, 2);
  j += ",\"td\":" + num(dewPoint(curT, curH), 2);
  j += ",\"alt\":" + String(altitudeM, 1);
  j += ",\"interval\":" + String(logIntervalMin);
  j += ",\"logCount\":" + String(logCount);
  j += ",\"logFull\":" + String(logFull ? "true" : "false");
  j += ",\"rssi\":" + String(WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0);
  j += ",\"uptime\":" + String(millis() / 1000);
  j += ",\"fw\":\"" FW_VERSION "\"}";
  sendJson(j);
}

void handleHistory() {
  long since = server.hasArg("since") ? server.arg("since").toInt() : 0;
  File f = LittleFS.open(LOG_PATH, "r");
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "text/csv", "");
  if (f) {
    String chunk;
    while (f.available()) {
      String line = f.readStringUntil('\n');
      if (line.length() == 0 || !isDigit(line[0])) continue;   // пропуск заголовка
      if (line.toInt() <= since) continue;
      chunk += line; chunk += '\n';
      if (chunk.length() > 1024) { server.sendContent(chunk); chunk = ""; }
    }
    if (chunk.length()) server.sendContent(chunk);
    f.close();
  }
  server.sendContent("");
}

void handleLogCsv() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) { server.send(404, "text/plain", "log is empty"); return; }
  server.sendHeader("Content-Disposition", "attachment; filename=meteo_log.csv");
  server.streamFile(f, "text/csv");
  f.close();
}

void handleConfig() {
  if (server.method() == HTTP_POST) {
    if (server.hasArg("alt")) altitudeM = constrain(server.arg("alt").toFloat(), -100.0f, 5000.0f);
    if (server.hasArg("interval")) logIntervalMin = constrain(server.arg("interval").toInt(), 1, 180);
    prefs.putFloat("alt", altitudeM);
    prefs.putUInt("interval", logIntervalMin);
    lastLogSlot = -1;
  }
  String j = "{\"alt\":" + String(altitudeM, 1) + ",\"interval\":" + String(logIntervalMin) +
             ",\"ssid\":\"" + jsonEscape(wifiSsid) + "\",\"ap\":" + String(apActive ? "true" : "false") + "}";
  sendJson(j);
}

void handleTime() {
  long epoch = server.arg("epoch").toInt();
  // время из телефона принимаем, если нет синхронизации с интернетом
  if (epoch > 1700000000 && (!ntpSynced || !timeValid())) {
    struct timeval tv = { epoch, 0 };
    settimeofday(&tv, nullptr);
    lastLogSlot = -1;
    sendJson("{\"set\":true}");
  } else {
    sendJson("{\"set\":false}");
  }
}

void handleWifi() {
  if (!server.hasArg("ssid")) { sendJson("{\"error\":\"ssid\"}", 400); return; }
  prefs.putString("ssid", server.arg("ssid"));
  prefs.putString("pass", server.arg("pass"));
  sendJson("{\"saved\":true,\"restart\":true}");
  delay(500);
  ESP.restart();
}

void handleClear() {
  LittleFS.remove(LOG_PATH);
  logCount = 0; logFull = false; lastLogSlot = -1;
  sendJson("{\"cleared\":true}");
}

void handleRoot() {
  String h = F("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
               "<meta http-equiv=refresh content=10><title>Метеостанция</title>"
               "<body style='font-family:sans-serif;max-width:420px;margin:20px auto'><h2>Метеостанция</h2>");
  float p0 = seaLevel(curP);
  h += "<p>Температура: <b>" + num(curT, 1) + " °C</b></p>";
  h += "<p>Влажность: <b>" + num(curH, 0) + " %</b></p>";
  h += "<p>Давление: <b>" + num(p0, 1) + " гПа (" + num(p0 * 0.750062, 1) + " мм рт. ст.)</b></p>";
  h += "<p>Точка росы: <b>" + num(dewPoint(curT, curH), 1) + " °C</b></p>";
  h += "<p>Записей в журнале: " + String(logCount) + " · <a href=/api/log.csv>скачать CSV</a></p></body>";
  server.send(200, "text/html; charset=utf-8", h);
}

void setupServer() {
  server.on("/", handleRoot);
  server.on("/api/now", handleNow);
  server.on("/api/history", handleHistory);
  server.on("/api/log.csv", handleLogCsv);
  server.on("/api/config", handleConfig);
  server.on("/api/time", HTTP_POST, handleTime);
  server.on("/api/wifi", HTTP_POST, handleWifi);
  server.on("/api/clear", HTTP_POST, handleClear);
  server.onNotFound([]() { server.send(404, "text/plain", "not found"); });
  server.begin();
}

// ================= setup / loop =================
void setup() {
  Serial.begin(115200);
  Wire.begin(SDA_PIN, SCL_PIN);

  prefs.begin("meteo", false);
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  altitudeM = prefs.getFloat("alt", altitudeM);
  logIntervalMin = prefs.getUInt("interval", logIntervalMin);

  hasOLED = display.begin(SSD1306_SWITCHCAPVCC, 0x3C);   // если экран пустой — попробуйте адрес 0x3D
  if (hasOLED) { display.cp437(true);                    // правильная кодовая таблица: символ 247 = «°»
                 display.clearDisplay(); display.setTextColor(SSD1306_WHITE);
                 display.setCursor(0, 0); display.print(F("Starting...")); display.display(); }

  hasBME = bme.begin(0x76) || bme.begin(0x77);
  if (hasBME) {
    isBME280 = (bme.sensorID() == 0x60);   // 0x60 — BME280, 0x58 — BMP280 (без влажности)
    if (!isBME280) Serial.println(F("WARNING: BMP280 detected, no humidity sensor"));
    bme.setSampling(Adafruit_BME280::MODE_FORCED,
                    Adafruit_BME280::SAMPLING_X1, Adafruit_BME280::SAMPLING_X1,
                    Adafruit_BME280::SAMPLING_X1, Adafruit_BME280::FILTER_OFF);
    measure();
  } else {
    Serial.println(F("BME280 not found: check wiring"));
  }

  fsOk = LittleFS.begin(true);             // true — отформатировать при первом запуске
  if (fsOk) countLog();

  sntp_set_time_sync_notification_cb(onNtpSync);
  configTzTime("MSK-3", "ru.pool.ntp.org", "pool.ntp.org", "time.google.com");

  startWiFi();
  setupServer();
  Serial.print(F("IP: ")); Serial.println(ipText());
  showScreen();
}

void loop() {
  server.handleClient();
  if (millis() - lastMeasure >= MEASURE_PERIOD_MS) {
    lastMeasure = millis();
    measure();
    logIfDue();
    showScreen();
  }
  maintainWiFi();
  delay(2);
}
