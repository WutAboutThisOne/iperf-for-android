package local.net.iperf.core

import org.json.JSONException
import org.json.JSONObject

/** Events decoded from `iperf3 --json-stream` output (one JSON object per line). */
sealed interface IperfEvent {
    data class Started(
        val version: String?,
        val remoteHost: String?,
        val remotePort: Int?,
        val protocol: String?,
        val streams: Int?,
        val durationSec: Int?,
    ) : IperfEvent

    data class Interval(val point: IntervalPoint) : IperfEvent
    data class Finished(val summary: Summary) : IperfEvent
    data class Error(val message: String) : IperfEvent
}

object IperfJson {

    /** Parses one line; returns null for blank, non-JSON or uninteresting lines. */
    fun parseLine(line: String): IperfEvent? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        val obj = try { JSONObject(t) } catch (_: JSONException) { return null }
        return when (obj.optString("event")) {
            "start" -> obj.optJSONObject("data")?.let(::parseStart)
            "interval" -> obj.optJSONObject("data")?.let(::parseInterval)
            "end" -> obj.optJSONObject("data")?.let(::parseEnd)
            "error" -> IperfEvent.Error(obj.optString("data").ifBlank { "Unknown iperf3 error" })
            else -> null
        }
    }

    fun parseAll(text: String): List<IperfEvent> = text.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseStart(d: JSONObject): IperfEvent.Started {
        val conn = d.optJSONObject("connecting_to")
        val ts = d.optJSONObject("test_start")
        return IperfEvent.Started(
            version = d.optStringOrNull("version"),
            remoteHost = conn?.optStringOrNull("host"),
            remotePort = conn?.optIntOrNull("port"),
            protocol = ts?.optStringOrNull("protocol"),
            streams = ts?.optIntOrNull("num_streams"),
            durationSec = ts?.optIntOrNull("duration"),
        )
    }

    private fun parseInterval(d: JSONObject): IperfEvent.Interval? {
        // With one stream older iperf builds omit "sum"; fall back to the first stream.
        val sum = d.optJSONObject("sum") ?: d.optJSONArray("streams")?.optJSONObject(0) ?: return null
        val reverse = d.optJSONObject("sum_bidir_reverse")
        var up: Double? = null
        var down: Double? = null
        fun assign(o: JSONObject) {
            val mbps = o.optDouble("bits_per_second", Double.NaN).takeUnless { it.isNaN() }?.div(1e6) ?: return
            if (o.optBoolean("sender", true)) up = mbps else down = mbps
        }
        assign(sum)
        reverse?.let(::assign)
        return IperfEvent.Interval(
            IntervalPoint(
                endSec = sum.optDouble("end", 0.0),
                upMbps = up,
                downMbps = down,
                omitted = sum.optBoolean("omitted", false),
            )
        )
    }

    private fun parseEnd(d: JSONObject): IperfEvent.Finished? {
        val sent = d.optJSONObject("sum_sent")
        val recv = d.optJSONObject("sum_received")
        if (sent == null && recv == null) return null // e.g. {"event":"end","data":{}} after an error
        val isUdp = d.optJSONObject("sum")?.has("jitter_ms") == true || recv?.has("jitter_ms") == true
        // Client-sent traffic has sender=true in sum_sent; -R flips it.
        val forwardIsUpload = sent?.optBoolean("sender", true) ?: true
        val forward = flow(sent, recv ?: d.optJSONObject("sum"), isUdp)

        val rSent = d.optJSONObject("sum_sent_bidir_reverse")
        val rRecv = d.optJSONObject("sum_received_bidir_reverse")
        val reverse = if (rSent != null || rRecv != null) flow(rSent, rRecv ?: d.optJSONObject("sum_bidir_reverse"), isUdp) else null

        val (upload, download) = when {
            reverse != null -> forward to reverse
            forwardIsUpload -> forward to null
            else -> null to forward
        }
        return IperfEvent.Finished(
            Summary(upload = upload, download = download, durationSec = sent?.optDoubleOrNull("seconds") ?: recv?.optDoubleOrNull("seconds"))
        )
    }

    private fun flow(sent: JSONObject?, recv: JSONObject?, udp: Boolean): FlowSummary = FlowSummary(
        senderMbps = sent?.optDoubleOrNull("bits_per_second")?.div(1e6),
        receiverMbps = recv?.optDoubleOrNull("bits_per_second")?.div(1e6),
        bytesSent = sent?.optLongOrNull("bytes"),
        bytesReceived = recv?.optLongOrNull("bytes"),
        retransmits = if (udp) null else sent?.optIntOrNull("retransmits"),
        jitterMs = if (udp) recv?.optDoubleOrNull("jitter_ms") else null,
        lostPercent = if (udp) recv?.optDoubleOrNull("lost_percent") else null,
        lostPackets = if (udp) recv?.optLongOrNull("lost_packets") else null,
        packets = if (udp) (recv?.optLongOrNull("packets") ?: sent?.optLongOrNull("packets")) else null,
    )
}

internal fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
internal fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
internal fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
internal fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null
