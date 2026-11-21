package local.net.iperf.core

/**
 * Bounded storage for a run's intervals, so an endless (-t 0) test can run for hours without memory growing.
 *
 * - [all] keeps the whole run at most [maxPoints] long: when full, neighbouring points are merged in pairs
 *   (halving the resolution), so the saved graph still covers the entire run.
 * - [window] is the last [windowSec] seconds at full 1 s resolution, for the live, scrolling graph.
 * - [summary] is computed from running totals of every interval, so it stays exact after merging.
 */
class PointBuffer(private val maxPoints: Int = 1800, private val windowSec: Int = 300) {
    private val merged = ArrayList<IntervalPoint>()
    private val recent = ArrayDeque<IntervalPoint>()
    /** How many raw intervals each point in [merged] currently represents. */
    private var resolution = 1
    private val pending = ArrayList<IntervalPoint>()

    private var upSum = 0.0; private var upN = 0
    private var downSum = 0.0; private var downN = 0
    private var upSumAll = 0.0; private var upNAll = 0
    private var downSumAll = 0.0; private var downNAll = 0
    private var upPeak: Double? = null; private var upMin: Double? = null
    private var downPeak: Double? = null; private var downMin: Double? = null
    private var upBytes = 0.0; private var downBytes = 0.0
    var count = 0; private set
    var lastEndSec = 0.0; private set
    var last: IntervalPoint? = null; private set

    fun add(p: IntervalPoint) {
        val dt = (p.endSec - lastEndSec).coerceAtLeast(0.0)
        count++
        lastEndSec = p.endSec
        last = p
        p.upMbps?.let {
            upSumAll += it; upNAll++; upBytes += it * 1e6 / 8.0 * dt
            if (!p.omitted) { upSum += it; upN++; upPeak = maxOf(it, upPeak ?: it); upMin = minOf(it, upMin ?: it) }
        }
        p.downMbps?.let {
            downSumAll += it; downNAll++; downBytes += it * 1e6 / 8.0 * dt
            if (!p.omitted) { downSum += it; downN++; downPeak = maxOf(it, downPeak ?: it); downMin = minOf(it, downMin ?: it) }
        }
        recent.addLast(p)
        while (recent.size > 1 && recent.last().endSec - recent.first().endSec >= windowSec) recent.removeFirst()
        pending += p
        if (pending.size >= resolution) {
            merged += mergeAll(pending)
            pending.clear()
            if (merged.size > maxPoints) compact()
        }
    }

    private fun compact() {
        val next = ArrayList<IntervalPoint>(merged.size / 2 + 1)
        var i = 0
        while (i < merged.size) {
            next += if (i + 1 < merged.size) mergeAll(listOf(merged[i], merged[i + 1])) else merged[i]
            i += 2
        }
        merged.clear(); merged.addAll(next)
        resolution *= 2
    }

    /** The whole run (downsampled when long), including any not-yet-merged tail. */
    fun all(): List<IntervalPoint> = if (pending.isEmpty()) merged.toList() else merged + mergeAll(pending)

    /** The last [windowSec] seconds at full resolution. */
    fun window(): List<IntervalPoint> = recent.toList()

    /** Mean of the counted (non-omitted) intervals — or of all of them if every interval was omitted. */
    fun summary(): Summary? {
        if (count == 0) return null
        val useAll = upN == 0 && downN == 0
        val up = if (useAll) upSumAll.takeIf { upNAll > 0 }?.div(upNAll) else upSum.takeIf { upN > 0 }?.div(upN)
        val down = if (useAll) downSumAll.takeIf { downNAll > 0 }?.div(downNAll) else downSum.takeIf { downN > 0 }?.div(downN)
        val useAllBytes = useAll
        return Summary(
            upload = up?.let { FlowSummary(senderMbps = it, receiverMbps = null, bytesSent = upBytes.toLong(),
                peakMbps = upPeak, minMbps = if (useAllBytes && upN == 0) null else upMin) },
            download = down?.let { FlowSummary(senderMbps = null, receiverMbps = it, bytesReceived = downBytes.toLong(),
                peakMbps = downPeak, minMbps = if (useAllBytes && downN == 0) null else downMin) },
            durationSec = lastEndSec,
        )
    }

    companion object {
        fun mergeAll(ps: List<IntervalPoint>): IntervalPoint {
            if (ps.size == 1) return ps[0]
            fun avg(sel: (IntervalPoint) -> Double?) = ps.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
            return IntervalPoint(endSec = ps.last().endSec, upMbps = avg { it.upMbps }, downMbps = avg { it.downMbps },
                omitted = ps.all { it.omitted })
        }
    }
}
