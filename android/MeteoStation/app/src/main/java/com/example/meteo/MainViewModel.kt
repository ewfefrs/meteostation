package com.example.meteo

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val host: String = "",
    val connected: Boolean = false,
    val now: NowData? = null,
    val history: List<Record> = emptyList(),
    val config: DeviceConfig? = null,
    val altitude: Double = 60.0,
    val lastUpdate: Long = 0,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val alertsEnabled: Boolean = true,
    val indoor: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val cache = HistoryCache(app)
    private val discovery = Discovery(app)

    private val _state = MutableStateFlow(
        UiState(host = settings.host, history = cache.load(), altitude = settings.altitude, alertsEnabled = settings.alertsEnabled, indoor = settings.indoor)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var timeSent = false
    private var lastHistorySync = 0L

    private fun api() = MeteoApi(_state.value.host)

    /** Опрос станции: текущие значения каждые 5 с, журнал — раз в минуту. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshNow()
                delay(5000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    private suspend fun refreshNow() {
        try {
            val api = api()
            val now = api.now()
            if (!timeSent) {
                api.setTime(System.currentTimeMillis() / 1000)   // станция без интернета получит время телефона
                timeSent = true
            }
            if (now.alt != settings.altitude) settings.altitude = now.alt
            _state.update { it.copy(connected = true, now = now, altitude = now.alt, lastUpdate = System.currentTimeMillis(), error = null) }
            if (System.currentTimeMillis() - lastHistorySync > 60_000) runCatching { syncHistory() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timeSent = false
            _state.update { it.copy(connected = false, error = "Нет связи со станцией (${_state.value.host})") }
        }
    }

    private suspend fun syncHistory() {
        val old = _state.value.history
        val fresh = api().history(old.lastOrNull()?.ts ?: 0L)
        _state.update { it.copy(history = cache.merge(old, fresh)) }
        lastHistorySync = System.currentTimeMillis()
    }

    fun setHost(host: String) {
        settings.host = host
        timeSent = false
        lastHistorySync = 0
        _state.update { it.copy(host = settings.host, connected = false, message = "Адрес сохранён: ${settings.host}") }
        viewModelScope.launch { refreshNow() }
    }

    fun discover() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Поиск станции в сети…") }
            val found = discovery.find()
            _state.update { it.copy(busy = false) }
            if (found != null) setHost(found)
            else _state.update { it.copy(message = "Станция не найдена. Введите IP-адрес с экрана станции.") }
        }
    }

    fun loadConfig() {
        viewModelScope.launch {
            runCatching { api().config() }.onSuccess { c -> _state.update { it.copy(config = c) } }
        }
    }

    fun saveConfig(alt: Double, interval: Int) {
        viewModelScope.launch {
            runCatching { api().setConfig(alt, interval) }
                .onSuccess { c ->
                    settings.altitude = c.alt
                    _state.update { it.copy(config = c, altitude = c.alt, message = "Настройки станции сохранены") }
                }
                .onFailure { _state.update { it.copy(message = "Не удалось сохранить: нет связи") } }
        }
    }

    fun sendWifi(ssid: String, pass: String) {
        viewModelScope.launch {
            runCatching { api().setWifi(ssid, pass) }
                .onSuccess {
                    _state.update {
                        it.copy(message = "Сеть сохранена, станция перезагружается. Подключите телефон к «$ssid» и нажмите «Найти станцию».")
                    }
                }
                .onFailure { _state.update { it.copy(message = "Не удалось передать настройки сети") } }
        }
    }

    /** Новая серия измерений: очистить журнал на станции и в приложении. */
    fun clearLog() {
        viewModelScope.launch {
            runCatching { api().clearLog() }
                .onSuccess {
                    cache.clear()
                    _state.update { it.copy(history = emptyList(), message = "Журнал очищен. Новая серия начата.") }
                }
                .onFailure { _state.update { it.copy(message = "Нет связи со станцией") } }
        }
    }

    fun setAlerts(enabled: Boolean) {
        settings.alertsEnabled = enabled
        Alerts.schedule(getApplication<Application>(), enabled)
        _state.update { it.copy(alertsEnabled = enabled) }
    }

    fun setIndoor(indoor: Boolean) {
        settings.indoor = indoor
        _state.update { it.copy(indoor = indoor) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Экспорт журнала в CSV для Excel и отправка через «Поделиться». */
    fun exportCsv(context: Context) {
        val s = _state.value
        if (s.history.isEmpty()) {
            _state.update { it.copy(message = "Журнал пуст") }
            return
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
        val dir = File(context.filesDir, "export").apply { mkdirs() }
        val name = "meteo_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date()) + ".csv"
        val file = File(dir, name)
        file.writeText(Csv.exportForExcel(s.history, s.altitude) { ts -> fmt.format(Date(ts * 1000)) })
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Журнал метеостанции")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Отправить журнал").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
