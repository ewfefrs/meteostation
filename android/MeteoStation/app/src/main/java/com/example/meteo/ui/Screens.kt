package com.example.meteo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meteo.MainViewModel
import com.example.meteo.Physics
import com.example.meteo.Record
import com.example.meteo.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private fun f(v: Double?, n: Int): String = if (v == null) "—" else String.format(RU, "%.${n}f", v)

@Composable
private fun InfoCard(title: String, value: String, note: String? = null, accent: Color = Navy, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Ice),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Muted, fontSize = 13.sp)
            Text(value, color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, color = Navy, fontSize = 13.sp)
        }
    }
}

// ======================= «Сейчас» =======================
@Composable
fun NowScreen(s: UiState) {
    val now = s.now
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusLine(s)
        if (now == null) {
            Text("Нет данных. Проверьте адрес станции во вкладке «Настройки».", color = Muted)
            return@Column
        }
        if (now.chip == "BMP280") Warning("Установлен BMP280 — у него нет датчика влажности. Нужен BME280.")
        if (!now.timeValid) Warning("На станции нет точного времени — записи в журнал не ведутся. Подключите её к интернету или откройте приложение рядом со станцией.")
        if (now.logFull) Warning("Журнал на станции заполнен. Сохраните данные и начните новую серию.")

        Card(colors = CardDefaults.cardColors(containerColor = Navy)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Температура", color = Color(0xFF9FB3C8), fontSize = 14.sp)
                Text(f(now.t, 1) + " °C", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("Точка росы " + f(now.td, 1) + " °C", color = Color(0xFF7FD6CB), fontSize = 15.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                "Влажность", f(now.h, 0) + " %",
                if (s.indoor) now.h?.let { Physics.humidityText(it) } else "на улице", Teal, Modifier.weight(1f)
            )
            InfoCard(
                "Давление", f(now.p0?.let { Physics.toMmHg(it) }, 1),
                "мм рт. ст. · " + f(now.p0, 1) + " гПа", Orange, Modifier.weight(1f)
            )
        }
        val dp = Physics.tendency3h(s.history, s.altitude)
        InfoCard(
            "Изменение давления за 3 часа",
            (if (dp != null && dp > 0) "+" else "") + f(dp, 1) + " гПа",
            Physics.tendencyText(dp),
            if (dp != null && dp <= -3) Orange else Navy,
            Modifier.fillMaxWidth()
        )
        Advice(if (s.indoor) now.h else null, dp)
        Text(
            "Давление приведено к уровню моря (высота датчика ${f(s.altitude, 0)} м). " +
                "Записей в журнале станции: ${now.logCount}, интервал ${now.interval} мин.",
            color = Muted, fontSize = 12.sp
        )
    }
}

@Composable
private fun StatusLine(s: UiState) {
    val fmt = SimpleDateFormat("HH:mm:ss", RU)
    val text = if (s.connected) "● Связь есть · ${s.host} · обновлено ${fmt.format(Date(s.lastUpdate))}"
    else "● " + (s.error ?: "Подключение к ${s.host}…")
    Text(text, color = if (s.connected) Teal else Orange, fontSize = 13.sp)
}

@Composable
private fun Warning(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFBE3D0))) {
        Text(text, Modifier.padding(12.dp), color = Navy, fontSize = 14.sp)
    }
}

@Composable
private fun Advice(h: Double?, dp: Double?) {
    val tips = buildList {
        if (h != null && h < 30) add("Воздух сухой: включите увлажнитель. Проветривание зимой влажность не повышает.")
        if (h != null && h > 60) add("Влажно: проветрите помещение, иначе возможен конденсат на окнах.")
        if (dp != null && dp <= -3) add("Давление быстро падает: вероятно приближение циклона — усиление ветра и осадки.")
    }
    if (tips.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD5F0EC))) {
        Column(Modifier.padding(12.dp)) {
            Text("Рекомендации", fontWeight = FontWeight.Bold, color = Navy)
            tips.forEach { Text("• $it", color = Navy, fontSize = 14.sp) }
        }
    }
}

// ======================= «Графики» =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(s: UiState, vm: MainViewModel) {
    val periods = listOf("24 ч" to 86400L, "3 сут" to 3 * 86400L, "7 сут" to 7 * 86400L, "Всё" to Long.MAX_VALUE)
    var sel by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val last = s.history.lastOrNull()?.ts ?: 0L
    val span = periods[sel].second
    val data: List<Record> = if (span == Long.MAX_VALUE) s.history else s.history.filter { it.ts >= last - span }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, (name, _) ->
                FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(name) })
            }
        }
        val p0 = data.map { it.ts to Physics.seaLevel(it.p, s.altitude) }
        ChartBlock("Давление на уровне моря", "гПа", listOf(Series(p0, Orange)), p0.map { it.second })
        val t = data.map { it.ts to it.t }
        val td = data.mapNotNull { r -> r.h?.let { r.ts to Physics.dewPoint(r.t, it) } }
        ChartBlock("Температура и точка росы", "°C", listOf(Series(t, Navy), Series(td, Teal)), t.map { it.second })
        Text("— температура   — точка росы", color = Muted, fontSize = 12.sp)
        val h = data.mapNotNull { r -> r.h?.let { r.ts to it } }
        ChartBlock("Относительная влажность", "%", listOf(Series(h, Blue)), h.map { it.second })
        Spacer(Modifier.height(4.dp))
        Button(onClick = { vm.exportCsv(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Экспорт журнала в CSV (Excel)")
        }
        Text("Записей в приложении: ${s.history.size}", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ChartBlock(title: String, unit: String, series: List<Series>, values: List<Double>) {
    Text(title, fontWeight = FontWeight.Bold, color = Navy)
    LineChart(series, unit)
    Physics.stats(values)?.let {
        Text(
            "мин ${f(it.min, 1)} · средн ${f(it.avg, 1)} · макс ${f(it.max, 1)} $unit",
            color = Muted, fontSize = 12.sp
        )
    }
    HorizontalDivider()
}

// ======================= «Настройки» =======================
@Composable
fun SettingsScreen(s: UiState, vm: MainViewModel) {
    var host by remember(s.host) { mutableStateOf(s.host) }
    var alt by remember(s.config) { mutableStateOf(s.config?.alt?.let { f(it, 0) } ?: f(s.altitude, 0)) }
    var interval by remember(s.config) { mutableStateOf((s.config?.interval ?: s.now?.interval ?: 10).toString()) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(s.connected) { if (s.connected) vm.loadConfig() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Section("Подключение к станции")
        OutlinedTextField(host, { host = it }, label = { Text("IP-адрес станции") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setHost(host) }) { Text("Подключиться") }
            OutlinedButton(onClick = { vm.discover() }, enabled = !s.busy) { Text("Найти станцию") }
        }
        Hint("Адрес показан в нижней строке экрана станции. Без домашней сети станция создаёт точку доступа «MeteoStation» (пароль meteo2026), адрес 192.168.4.1.")

        Section("Измерения")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                alt, { alt = it }, label = { Text("Высота датчика, м") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                interval, { interval = it }, label = { Text("Интервал, мин") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
            )
        }
        Button(onClick = {
            val a = alt.replace(',', '.').toDoubleOrNull()
            val i = interval.toIntOrNull()
            if (a != null && i != null) vm.saveConfig(a, i)
        }, enabled = s.connected) { Text("Сохранить на станции") }
        Hint("Высота = высота земли у дома по карте + (этаж − 1) × 3 м + 1 м. Нужна для приведения давления к уровню моря.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Станция в помещении (оценка влажности по ГОСТ и совет по увлажнению). Выключите на время измерений на улице.",
                Modifier.weight(1f), color = Navy)
            Spacer(Modifier.width(8.dp))
            Switch(checked = s.indoor, onCheckedChange = { vm.setIndoor(it) })
        }


        Section("Домашняя сеть Wi-Fi для станции")
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Имя сети (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { if (ssid.isNotBlank()) vm.sendWifi(ssid, pass) }, enabled = s.connected) { Text("Передать станции") }
        Hint("С домашней сетью станция берёт точное время из интернета, а телефон видит её из любой комнаты.")

        Section("Уведомления")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Падение давления ≥ 3 гПа за 3 ч; влажность < 30 % (для станции в помещении)", Modifier.weight(1f), color = Navy)
            Spacer(Modifier.width(8.dp))
            Switch(checked = s.alertsEnabled, onCheckedChange = { vm.setAlerts(it) })
        }

        Section("Серия измерений")
        OutlinedButton(onClick = { confirmClear = true }, enabled = s.connected) { Text("Начать новую серию (очистить журнал)") }
        s.now?.let { Hint("Прошивка ${it.fw} · датчик ${it.chip} · время ${if (it.ntp) "из интернета" else if (it.timeValid) "из телефона" else "не задано"}") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Записи будут удалены на станции и в приложении. Сначала сохраните CSV во вкладке «Графики».") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearLog() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Navy, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Muted, fontSize = 12.sp)
}
