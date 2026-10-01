package com.example.meteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Клиент HTTP API станции. host — IP-адрес или имя (например, 192.168.4.1). */
class MeteoApi(host: String) {
    private val base = "http://" + host.trim().removePrefix("http://").trimEnd('/')

    private suspend fun request(method: String, path: String, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val query = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            val c = URL(base + path + query).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 20000
            c.requestMethod = method
            try {
                if (method == "POST") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(0)
                    c.outputStream.close()
                }
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

    private fun JSONObject.num(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    suspend fun now(): NowData {
        val j = JSONObject(request("GET", "/api/now"))
        return NowData(
            ok = j.optBoolean("ok"),
            chip = j.optString("chip"),
            ts = j.optLong("ts"),
            timeValid = j.optBoolean("timeValid"),
            ntp = j.optBoolean("ntp"),
            t = j.num("t"), h = j.num("h"), p = j.num("p"), p0 = j.num("p0"), td = j.num("td"),
            alt = j.optDouble("alt", 0.0),
            interval = j.optInt("interval", 10),
            logCount = j.optInt("logCount"),
            logFull = j.optBoolean("logFull"),
            rssi = j.optInt("rssi"),
            fw = j.optString("fw"),
        )
    }

    suspend fun history(since: Long): List<Record> =
        Csv.parseLog(request("GET", "/api/history", mapOf("since" to since.toString())))

    suspend fun config(): DeviceConfig {
        val j = JSONObject(request("GET", "/api/config"))
        return DeviceConfig(j.optDouble("alt", 0.0), j.optInt("interval", 10), j.optString("ssid"), j.optBoolean("ap"))
    }

    suspend fun setConfig(alt: Double, interval: Int): DeviceConfig {
        val j = JSONObject(request("POST", "/api/config", mapOf("alt" to alt.toString(), "interval" to interval.toString())))
        return DeviceConfig(j.optDouble("alt", 0.0), j.optInt("interval", 10), j.optString("ssid"), j.optBoolean("ap"))
    }

    /** Передать станции время телефона (нужно, если у станции нет интернета). */
    suspend fun setTime(epochSeconds: Long): Boolean =
        JSONObject(request("POST", "/api/time", mapOf("epoch" to epochSeconds.toString()))).optBoolean("set")

    suspend fun setWifi(ssid: String, pass: String) {
        request("POST", "/api/wifi", mapOf("ssid" to ssid, "pass" to pass))
    }

    suspend fun clearLog() {
        request("POST", "/api/clear")
    }
}
