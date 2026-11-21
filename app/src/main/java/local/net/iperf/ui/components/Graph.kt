package local.net.iperf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.formatMbps
import local.net.iperf.ui.theme.Series
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 1, 2, 5 × 10^k at or above [v]. */
fun niceStep(v: Double): Double {
    if (v <= 0) return 1.0
    val base = 10.0.pow(floor(log10(v)))
    val f = v / base
    return base * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
}

/** Axis scale: (max, step) with 2–5 round-number ticks above zero. */
fun axisScale(peak: Double): Pair<Double, Double> {
    val target = if (peak <= 0) 1.0 else peak * 1.08
    val step = niceStep(target / 4)
    return ceil(target / step) * step to step
}

private fun axisLabel(v: Double, max: Double): String {
    fun trim(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
    return when {
        max >= 2000 -> trim(v / 1000, 2) + "G"
        else -> trim(v, 2)
    }
}

@Composable
fun ThroughputGraph(
    points: List<IntervalPoint>,
    totalSec: Int,
    modifier: Modifier = Modifier,
    omitSec: Int = 0,
    /** When set, the x axis is a scrolling window of this many seconds ending at the newest point (endless runs). */
    windowSec: Int? = null,
    /** 0.1.4: taller on an unfolded screen. */
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val up = Series.up()
    val down = Series.down()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val omitShade = MaterialTheme.colorScheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val hasUp = points.any { it.upMbps != null }
    val hasDown = points.any { it.downMbps != null }
    val peak = points.maxOfOrNull { maxOf(it.upMbps ?: 0.0, it.downMbps ?: 0.0) } ?: 0.0
    val (yMax, yStep) = remember(peak) { axisScale(peak) }
    val lastEnd = points.lastOrNull()?.endSec ?: 0.0
    val xMax = if (windowSec != null) maxOf(lastEnd, windowSec.toDouble()) else maxOf(totalSec.toDouble() + omitSec, lastEnd, 1.0)
    val xMin = if (windowSec != null) xMax - windowSec else 0.0
    val desc = buildString {
        append("Throughput graph, ${points.size} intervals")
        points.lastOrNull()?.let { p -> p.upMbps?.let { append(", upload ${formatMbps(it)}") }; p.downMbps?.let { append(", download ${formatMbps(it)}") } }
    }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }) {
            val left = 40.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
            val w = size.width - left; val h = size.height - bottom - top
            fun x(sec: Double) = left + ((sec - xMin) / (xMax - xMin) * w).toFloat()
            fun y(v: Double) = top + h - (v / yMax * h).toFloat()

            if (omitSec > 0 && omitSec > xMin) drawRect(omitShade, Offset(left, top), Size(x(omitSec.toDouble()) - left, h))
            val ticks = kotlin.math.round(yMax / yStep).toInt()
            for (i in 0..ticks) {
                val v = yStep * i
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = if (i == 0) 1.5f else 1f)
                val t = measurer.measure(axisLabel(v, yMax), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
            }
            val step = timeStep(xMax - xMin)
            var s = ceil(xMin / step - 1e-9) * step
            while (s <= xMax + 0.01) {
                val t = measurer.measure(timeLabel(s, step), labelStyle)
                val xx = (x(s) - t.size.width / 2f).coerceIn(left - t.size.width / 2f, size.width - t.size.width)
                drawText(t, topLeft = Offset(xx, top + h + 3.dp.toPx()))
                s += step
            }
            // Interval starts: first one at 0 (or the previous end) - assume 1 s intervals.
            val firstStart = ((points.firstOrNull()?.endSec ?: 1.0) - 1.0).coerceAtLeast(xMin)
            if (hasDown) series(points.mapNotNull { p -> p.downMbps?.let { Offset(x(p.endSec), y(it)) } }, down, top + h, x(firstStart))
            if (hasUp) series(points.mapNotNull { p -> p.upMbps?.let { Offset(x(p.endSec), y(it)) } }, up, top + h, x(firstStart))
        }
        if (hasUp || hasDown) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = 40.dp)) {
                if (hasUp) Legend(up, "Upload")
                if (hasDown) Legend(down, "Download")
                Text("Mbps per 1 s interval", style = labelStyle)
            }
        }
    }
}

/** X-axis tick spacing for a time range, giving at most ~6 labels. */
fun timeStep(range: Double): Double {
    if (range <= 15) return 5.0
    if (range <= 40) return 10.0
    if (range <= 120) return 30.0
    return listOf(60.0, 120.0, 300.0, 600.0, 1200.0, 1800.0, 3600.0, 7200.0, 14400.0, 28800.0, 43200.0, 86400.0)
        .firstOrNull { range / it <= 6 } ?: 86400.0
}

/** "30s" below two minutes, "5m" for whole minutes, "2h" / "1h30" for long runs. */
fun timeLabel(sec: Double, step: Double): String {
    val t = sec.toLong()
    return when {
        step < 60 -> "${t}s"
        t % 3600 == 0L && t > 0 && step >= 1800 -> "${t / 3600}h"
        t >= 3600 && step >= 600 -> "${t / 3600}h%02d".format((t % 3600) / 60)
        else -> "${t / 60}m"
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun DrawScope.series(pts: List<Offset>, color: Color, baseline: Float, start: Float) {
    if (pts.isEmpty()) return
    val line = Path().apply {
        // Each value covers its whole interval, so start the line at the first interval's start.
        moveTo(start, pts.first().y)
        lineTo(pts.first().x, pts.first().y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    run {
        val fill = Path().apply {
            addPath(line)
            lineTo(pts.last().x, baseline); lineTo(start, baseline); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), endY = baseline))
    }
    drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color, radius = 3.5.dp.toPx(), center = pts.last())
}
