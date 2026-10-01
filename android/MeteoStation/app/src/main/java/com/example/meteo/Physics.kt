package com.example.meteo

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Физические расчёты — те же формулы, что в работе и в прошивке. */
object Physics {
    /** Приведение давления к уровню моря (стандартная атмосфера), гПа. */
    fun seaLevel(p: Double, altitude: Double): Double = p / (1.0 - altitude / 44330.0).pow(5.255)

    /** Точка росы по формуле Магнуса (a = 17,625; b = 243,04 °C). */
    fun dewPoint(t: Double, rh: Double): Double {
        val a = 17.625
        val b = 243.04
        val g = ln(rh / 100.0) + a * t / (b + t)
        return b * g / (a - g)
    }

    fun toMmHg(hPa: Double): Double = hPa * 0.750062

    /**
     * Барическая тенденция: изменение давления на уровне моря за 3 часа, гПа.
     * Берётся запись, ближайшая к моменту «последняя − 3 ч» (допуск ±20 мин).
     */
    fun tendency3h(records: List<Record>, altitude: Double): Double? {
        if (records.size < 2) return null
        val last = records.last()
        val target = last.ts - 3 * 3600
        val past = records.minByOrNull { abs(it.ts - target) } ?: return null
        if (abs(past.ts - target) > 20 * 60) return null
        return seaLevel(last.p, altitude) - seaLevel(past.p, altitude)
    }

    fun tendencyText(dp: Double?): String = when {
        dp == null -> "мало данных (нужно 3 часа записей)"
        dp <= -3.0 -> "быстро падает — вероятен циклон, ветер и осадки"
        dp <= -1.0 -> "падает"
        dp < 1.0 -> "стабильно"
        dp < 3.0 -> "растёт"
        else -> "быстро растёт — прояснение, ветер"
    }

    /** Оценка влажности для жилой комнаты зимой по ГОСТ 30494-2011. */
    fun humidityText(rh: Double): String = when {
        rh < 30 -> "сухо (норма 30–45 %)"
        rh <= 45 -> "оптимально"
        rh <= 60 -> "допустимо"
        else -> "влажно (выше 60 %)"
    }

    data class Stats(val min: Double, val max: Double, val avg: Double)

    fun stats(values: List<Double>): Stats? =
        if (values.isEmpty()) null else Stats(values.min(), values.max(), values.average())
}
