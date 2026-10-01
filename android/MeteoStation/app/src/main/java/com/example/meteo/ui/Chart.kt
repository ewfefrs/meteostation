package com.example.meteo.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Серия точек графика: время (с) → значение. */
class Series(val points: List<Pair<Long, Double>>, val color: Color)

/** Линейный график с осями и подписями (без сторонних библиотек). */
@Composable
fun LineChart(series: List<Series>, unit: String, modifier: Modifier = Modifier) {
    val all = series.flatMap { it.points }
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val left = 56.dp.toPx()
        val bottom = 26.dp.toPx()
        val top = 8.dp.toPx()
        val right = 8.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val text = Paint().apply {
            isAntiAlias = true
            textSize = 11.dp.toPx()
            color = Muted.toArgb()
        }
        if (all.size < 2) {
            drawContext.canvas.nativeCanvas.drawText("Недостаточно данных", left, top + h / 2, text)
            return@Canvas
        }
        val t0 = all.minOf { it.first }
        val t1 = max(all.maxOf { it.first }, t0 + 1)
        var v0 = all.minOf { it.second }
        var v1 = all.maxOf { it.second }
        val pad = max((v1 - v0) * 0.1, 0.5)
        v0 = floor(v0 - pad)
        v1 = ceil(v1 + pad)

        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0) * w
        fun y(v: Double) = top + ((v1 - v) / (v1 - v0)).toFloat() * h

        // сетка и подписи значений
        val grid = Color(0xFFDCE3EA)
        for (i in 0..4) {
            val v = v0 + (v1 - v0) * i / 4
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(left + w, yy), 1f)
            val label = if (abs(v1 - v0) < 10) String.format(Locale.ROOT, "%.1f", v) else String.format(Locale.ROOT, "%.0f", v)
            drawContext.canvas.nativeCanvas.drawText("$label $unit", 2f, yy + 4.dp.toPx(), text)
        }
        // подписи времени
        val span = t1 - t0
        val fmt = SimpleDateFormat(if (span > 2 * 86400) "dd.MM" else "HH:mm", Locale.forLanguageTag("ru"))
        for (i in 0..3) {
            val t = t0 + span * i / 3
            val label = fmt.format(Date(t * 1000))
            val xx = x(t) - text.measureText(label) / 2
            drawContext.canvas.nativeCanvas.drawText(label, xx.coerceIn(left, left + w - text.measureText(label)), top + h + 18.dp.toPx(), text)
        }
        // линии
        series.forEach { s ->
            if (s.points.size < 2) return@forEach
            val path = Path()
            s.points.forEachIndexed { i, (t, v) ->
                // разрыв линии, если между записями больше 1 часа (станция была выключена)
                if (i == 0 || t - s.points[i - 1].first > 3600) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
            }
            drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}
