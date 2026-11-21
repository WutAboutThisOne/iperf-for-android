package local.net.iperf.core

import java.util.Locale
import kotlin.math.abs

/** "940 Mbps", "87.3 Mbps", "2.37 Gbps", "512 kbps". */
fun formatMbps(mbps: Double?): String {
    if (mbps == null || mbps.isNaN()) return "—"
    val a = abs(mbps)
    return when {
        // Thresholds sit where rounding would otherwise print "100.0 Mbps" or "1000 Mbps".
        a >= 999.5 -> String.format(Locale.US, "%.2f Gbps", mbps / 1000)
        a >= 99.95 -> String.format(Locale.US, "%.0f Mbps", mbps)
        a >= 0.9995 -> String.format(Locale.US, "%.1f Mbps", mbps)
        a == 0.0 -> "0 Mbps"
        else -> String.format(Locale.US, "%.0f kbps", mbps * 1000)
    }
}

/** Number + unit separately, for big live readouts. */
fun splitMbps(mbps: Double?): Pair<String, String> {
    val s = formatMbps(mbps)
    val i = s.lastIndexOf(' ')
    return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1)
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1000 && u < units.lastIndex) { v /= 1000; u++ }
    return if (u == 0) "$bytes B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[u])
}

fun formatPercent(p: Double?): String = when {
    p == null -> "—"
    p == 0.0 -> "0%"
    p < 0.01 -> "<0.01%"
    p < 10 -> String.format(Locale.US, "%.2f%%", p)
    else -> String.format(Locale.US, "%.1f%%", p)
}

fun formatMs(ms: Double?): String = when {
    ms == null -> "—"
    ms < 10 -> String.format(Locale.US, "%.3f ms", ms)
    else -> String.format(Locale.US, "%.1f ms", ms)
}
