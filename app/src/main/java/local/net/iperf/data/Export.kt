package local.net.iperf.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the JSON and CSV history exports shared through the share sheet. Logins are never included,
 * and CSV cells that look like spreadsheet formulas (e.g. a hostile Wi-Fi name) are neutralised.
 */
object Export {
    val CSV_HEADER = listOf(
        "time", "server", "host", "port", "protocol", "direction", "duration_s", "parallel", "udp_target_mbps", "omit_s",
        "upload_sender_mbps", "upload_receiver_mbps", "upload_bytes_sent", "upload_bytes_received",
        "upload_avg_mbps", "upload_peak_mbps", "upload_min_mbps", "upload_retransmits", "upload_jitter_ms", "upload_loss_pct",
        "download_sender_mbps", "download_receiver_mbps", "download_bytes_sent", "download_bytes_received",
        "download_avg_mbps", "download_peak_mbps", "download_min_mbps", "download_retransmits", "download_jitter_ms", "download_loss_pct",
        "network", "ssid", "band", "rssi_dbm", "link_mbps", "cellular_type", "vpn", "stopped_early", "outcome", "error_title", "error_detail", "iperf_version",
    )

    fun json(records: List<TestRecord>, exportedAt: Long = System.currentTimeMillis()): String = JSONObject()
        .put("app", "iPerf for Android")
        .put("schema", Codec.SCHEMA)
        .put("exported_at", iso(exportedAt, ZoneId.systemDefault()))
        .put("results", JSONArray().apply { records.forEach { put(Codec.record(it).put("time", iso(it.timestamp, ZoneId.systemDefault()))) } })
        .toString(2)

    fun csv(records: List<TestRecord>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(CSV_HEADER.joinToString(","))
        records.forEach { r ->
            val o = r.options; val u = r.summary.upload; val d = r.summary.download; val n = r.network
            val row = listOf(
                iso(r.timestamp, zone), r.serverLabel, o.host, o.port, o.protocol.name, o.direction.name, o.durationSec, o.parallel,
                if (o.protocol.name == "UDP") num(o.udpBandwidthMbps) else "", o.omitSec,
                num(u?.senderMbps), num(u?.receiverMbps), u?.bytesSent, u?.bytesReceived,
                num(u?.mbps), num(u?.peakMbps), num(u?.minMbps), u?.retransmits, num(u?.jitterMs), num(u?.lostPercent),
                num(d?.senderMbps), num(d?.receiverMbps), d?.bytesSent, d?.bytesReceived,
                num(d?.mbps), num(d?.peakMbps), num(d?.minMbps), d?.retransmits, num(d?.jitterMs), num(d?.lostPercent),
                n.type.name, n.ssid, n.band, n.rssiDbm, n.linkMbps, n.cellularType, n.vpn, r.stoppedEarly,
                when { r.failed -> "failed"; r.stoppedEarly -> "stopped"; else -> "finished" },
                r.errorTitle, r.errorDetail, r.iperfVersion,
            )
            appendLine(row.joinToString(",") { escape(it?.toString() ?: "") })
        }
    }

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || v.startsWith(" ") || v.endsWith(" "))
            "\"" + v.replace("\"", "\"\"") + "\""
        // Neutralise spreadsheet formula injection from e.g. a hostile SSID.
        else if (v.isNotEmpty() && v[0] in "=+@" ) "'$v" else v

    private fun num(v: Double?): String? = v?.let { java.math.BigDecimal(String.format(Locale.US, "%.3f", it)).stripTrailingZeros().toPlainString() }

    fun iso(millis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone).withNano(0))

    fun fileName(ext: String, now: Long = System.currentTimeMillis()): String =
        "iperf-history-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) + ".$ext"
}
