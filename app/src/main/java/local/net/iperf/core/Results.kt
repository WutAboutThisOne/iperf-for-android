package local.net.iperf.core

/** One reporting interval. Throughput is from the client's point of view: up = phone → server. */
data class IntervalPoint(
    val endSec: Double,
    val upMbps: Double? = null,
    val downMbps: Double? = null,
    val omitted: Boolean = false,
)

/** End-of-test numbers for one direction of traffic. */
data class FlowSummary(
    val senderMbps: Double?,
    val receiverMbps: Double?,
    val bytesSent: Long? = null,
    val bytesReceived: Long? = null,
    /** TCP only (reported by whichever side was sending). */
    val retransmits: Int? = null,
    /** UDP only. */
    val jitterMs: Double? = null,
    val lostPercent: Double? = null,
    val lostPackets: Long? = null,
    val packets: Long? = null,
    /** Peak and lowest one-second rates from the interval graph (non-omitted). */
    val peakMbps: Double? = null,
    val minMbps: Double? = null,
) {
    /**
     * Headline rate: what actually arrived, falling back to what was sent.
     * A 0 receiver figure is ignored when this is a TCP-style summary (no loss
     * stats) — iperf3 reports 0 received if the far side dies before it acks.
     * UDP summaries include [lostPercent], so a 0 receiver rate stays 0.
     */
    val mbps: Double?
        get() {
            val rx = receiverMbps
            val tx = senderMbps
            if (rx != null && rx > 0.0) return rx
            if (rx != null && lostPercent != null) return rx
            if (tx != null && tx > 0.0) return tx
            return rx ?: tx
        }
}

/** Average / peak / min / estimated bytes from interval points (non-omitted, or all if every point was omitted). */
fun intervalStats(points: List<IntervalPoint>, up: Boolean): Triple<Double?, Double?, Long?> {
    val counted = points.filterNot { it.omitted }.ifEmpty { points }
    val rates = counted.mapNotNull { if (up) it.upMbps else it.downMbps }
    if (rates.isEmpty()) return Triple(null, null, null)
    var prev = 0.0
    var bytes = 0.0
    var anyBytes = false
    for (p in counted) {
        val rate = (if (up) p.upMbps else p.downMbps) ?: continue
        val dt = (p.endSec - prev).coerceAtLeast(0.0)
        prev = p.endSec
        bytes += rate * 1e6 / 8.0 * dt
        anyBytes = true
    }
    return Triple(rates.maxOrNull(), rates.minOrNull(), if (anyBytes) bytes.toLong() else null)
}

fun flowFromIntervals(points: List<IntervalPoint>, up: Boolean): FlowSummary? {
    val counted = points.filterNot { it.omitted }.ifEmpty { points }
    val rates = counted.mapNotNull { if (up) it.upMbps else it.downMbps }
    if (rates.isEmpty()) return null
    val (peak, min, bytes) = intervalStats(points, up)
    val avg = rates.average()
    return if (up) FlowSummary(senderMbps = avg, receiverMbps = null, bytesSent = bytes, peakMbps = peak, minMbps = min)
    else FlowSummary(senderMbps = null, receiverMbps = avg, bytesReceived = bytes, peakMbps = peak, minMbps = min)
}

fun FlowSummary.enriched(points: List<IntervalPoint>, up: Boolean): FlowSummary {
    val (peak, min, bytesEst) = intervalStats(points, up)
    return copy(
        peakMbps = peakMbps ?: peak,
        minMbps = minMbps ?: min,
        bytesSent = bytesSent ?: if (up) bytesEst else bytesSent,
        bytesReceived = bytesReceived ?: if (!up) bytesEst else bytesReceived,
    )
}

data class Summary(
    val upload: FlowSummary? = null,
    val download: FlowSummary? = null,
    val durationSec: Double? = null,
)

fun Summary.enriched(points: List<IntervalPoint>): Summary = copy(
    upload = upload?.enriched(points, up = true) ?: flowFromIntervals(points, up = true),
    download = download?.enriched(points, up = false) ?: flowFromIntervals(points, up = false),
)

/** Fallback summary when a run is stopped before iperf3 prints its own: mean / peak / min of the intervals. */
fun summaryFromIntervals(points: List<IntervalPoint>): Summary? {
    if (points.isEmpty()) return null
    val up = flowFromIntervals(points, up = true)
    val down = flowFromIntervals(points, up = false)
    if (up == null && down == null) return null
    val counted = points.filterNot { it.omitted }.ifEmpty { points }
    return Summary(upload = up, download = down, durationSec = counted.last().endSec)
}
