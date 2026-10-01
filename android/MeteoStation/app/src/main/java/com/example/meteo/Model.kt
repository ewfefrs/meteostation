package com.example.meteo

/** Текущие показания станции (ответ /api/now). */
data class NowData(
    val ok: Boolean,
    val chip: String,
    val ts: Long,
    val timeValid: Boolean,
    val ntp: Boolean,
    val t: Double?,
    val h: Double?,
    val p: Double?,
    val p0: Double?,
    val td: Double?,
    val alt: Double,
    val interval: Int,
    val logCount: Int,
    val logFull: Boolean,
    val rssi: Int,
    val fw: String,
)

/** Одна запись журнала: время (UTC, с), температура °C, влажность % (null — нет датчика), давление гПа. */
data class Record(val ts: Long, val t: Double, val h: Double?, val p: Double)

/** Настройки станции (ответ /api/config). */
data class DeviceConfig(val alt: Double, val interval: Int, val ssid: String, val ap: Boolean)

object Csv {
    /** Разбор журнала станции: строки вида "ts;t;h;p". Заголовок и мусор пропускаются. */
    fun parseLog(text: String): List<Record> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size < 4) return@mapNotNull null
        val ts = f[0].toLongOrNull() ?: return@mapNotNull null
        val t = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val h = f[2].toDoubleOrNull()?.takeIf { it >= 0 }
        val p = f[3].toDoubleOrNull() ?: return@mapNotNull null
        Record(ts, t, h, p)
    }.toList()

    fun formatLog(records: List<Record>): String = buildString {
        records.forEach { r -> append("${r.ts};${r.t};${r.h ?: -1.0};${r.p}\n") }
    }

    /** CSV для Excel (русская локаль: разделитель «;», десятичная запятая). */
    fun exportForExcel(records: List<Record>, altitude: Double, formatTime: (Long) -> String): String = buildString {
        append('﻿')
        append("Дата и время;Unix-время;Температура, °C;Влажность, %;Давление, гПа;Давление на уровне моря, гПа;Точка росы, °C\n")
        fun d(v: Double?, n: Int) = if (v == null || v.isNaN()) "" else String.format(java.util.Locale.ROOT, "%.${n}f", v).replace('.', ',')
        records.forEach { r ->
            val p0 = Physics.seaLevel(r.p, altitude)
            val td = r.h?.let { Physics.dewPoint(r.t, it) }
            append("${formatTime(r.ts)};${r.ts};${d(r.t, 2)};${d(r.h, 1)};${d(r.p, 2)};${d(p0, 2)};${d(td, 2)}\n")
        }
    }
}
