package com.example.meteo

import android.content.Context
import java.io.File

/** Настройки приложения. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "192.168.4.1") ?: "192.168.4.1"
        set(v) = sp.edit().putString("host", v.trim()).apply()

    /** Высота датчика над уровнем моря — копия настройки станции для расчётов в приложении. */
    var altitude: Double
        get() = sp.getFloat("alt", 60f).toDouble()
        set(v) = sp.edit().putFloat("alt", v.toFloat()).apply()

    /** Станция в помещении (оценка влажности по ГОСТ имеет смысл только для помещения). */
    var indoor: Boolean
        get() = sp.getBoolean("indoor", true)
        set(v) = sp.edit().putBoolean("indoor", v).apply()

    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts", true)
        set(v) = sp.edit().putBoolean("alerts", v).apply()

    /** Порог падения давления за 3 ч, гПа. */
    var pressureDrop: Double
        get() = sp.getFloat("pdrop", 3f).toDouble()
        set(v) = sp.edit().putFloat("pdrop", v.toFloat()).apply()

    /** Нижняя граница влажности, %. */
    var humidityLow: Double
        get() = sp.getFloat("hlow", 30f).toDouble()
        set(v) = sp.edit().putFloat("hlow", v.toFloat()).apply()

    var lastPressureAlert: Long
        get() = sp.getLong("lastP", 0)
        set(v) = sp.edit().putLong("lastP", v).apply()

    var lastHumidityAlert: Long
        get() = sp.getLong("lastH", 0)
        set(v) = sp.edit().putLong("lastH", v).apply()
}

/** Локальная копия журнала станции: графики работают и без связи. */
class HistoryCache(context: Context) {
    private val file = File(context.filesDir, "history.csv")

    fun load(): List<Record> = if (file.exists()) Csv.parseLog(file.readText()) else emptyList()

    /** Добавить новые записи, убрать дубли, отсортировать по времени. */
    fun merge(old: List<Record>, new: List<Record>): List<Record> {
        if (new.isEmpty()) return old
        val merged = (old + new).associateBy { it.ts }.values.sortedBy { it.ts }
        file.writeText(Csv.formatLog(merged))
        return merged
    }

    fun clear() {
        file.delete()
    }
}
