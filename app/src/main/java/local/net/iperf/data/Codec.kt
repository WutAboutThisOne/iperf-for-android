package local.net.iperf.data

import local.net.iperf.core.Direction
import local.net.iperf.core.FlowSummary
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.NetType
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.Protocol
import local.net.iperf.core.Summary
import local.net.iperf.core.TestOptions
import local.net.iperf.core.optDoubleOrNull
import local.net.iperf.core.optIntOrNull
import local.net.iperf.core.optLongOrNull
import local.net.iperf.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/** JSON encoding for servers and history (also the JSON export format). */
object Codec {
    const val SCHEMA = 1 // history export format (unchanged in 0.1.1)

    // Schema 2 (0.1.1) adds optional web_url / manager fields; 0.1.0 files simply lack them and load with defaults.
    // Secrets are NOT part of this encoding: they live encrypted in SecretStore.
    fun server(s: Server): JSONObject = JSONObject().put("id", s.id).put("host", s.host).put("port", s.port).put("label", s.label)
        .apply {
            if (s.webUrl.isNotBlank()) put("web_url", s.webUrl)
            if (s.managerEnabled) put("manager_enabled", true)
            if (s.managerUrl.isNotBlank()) put("manager_url", s.managerUrl)
        }
    fun server(o: JSONObject) = Server(
        o.getString("id"), o.getString("host"), o.optInt("port", 5201), o.optString("label", ""),
        webUrl = o.optString("web_url", ""),
        managerEnabled = o.optBoolean("manager_enabled", false),
        managerUrl = o.optString("manager_url", ""),
    )

    fun options(o: TestOptions) = JSONObject()
        .put("host", o.host).put("port", o.port)
        .put("protocol", o.protocol.name).put("direction", o.direction.name)
        .put("duration_s", o.durationSec).put("parallel", o.parallel)
        .put("udp_target_mbps", o.udpBandwidthMbps).put("omit_s", o.omitSec)

    fun options(j: JSONObject) = TestOptions(
        host = j.getString("host"),
        port = j.optInt("port", 5201),
        protocol = enumOr(j.optString("protocol"), Protocol.TCP),
        direction = enumOr(j.optString("direction"), Direction.UPLOAD),
        durationSec = j.optInt("duration_s", 10),
        parallel = j.optInt("parallel", 1),
        udpBandwidthMbps = j.optDouble("udp_target_mbps", 100.0),
        omitSec = j.optInt("omit_s", 0),
    )

    private fun flow(f: FlowSummary?): Any = f?.let {
        JSONObject()
            .putOpt("sender_mbps", it.senderMbps).putOpt("receiver_mbps", it.receiverMbps)
            .putOpt("bytes_sent", it.bytesSent).putOpt("bytes_received", it.bytesReceived)
            .putOpt("retransmits", it.retransmits).putOpt("jitter_ms", it.jitterMs)
            .putOpt("lost_percent", it.lostPercent).putOpt("lost_packets", it.lostPackets).putOpt("packets", it.packets)
            .putOpt("peak_mbps", it.peakMbps).putOpt("min_mbps", it.minMbps)
    } ?: JSONObject.NULL

    private fun flow(j: JSONObject?): FlowSummary? = j?.let {
        FlowSummary(
            senderMbps = it.optDoubleOrNull("sender_mbps"), receiverMbps = it.optDoubleOrNull("receiver_mbps"),
            bytesSent = it.optLongOrNull("bytes_sent"), bytesReceived = it.optLongOrNull("bytes_received"),
            retransmits = it.optIntOrNull("retransmits"), jitterMs = it.optDoubleOrNull("jitter_ms"),
            lostPercent = it.optDoubleOrNull("lost_percent"), lostPackets = it.optLongOrNull("lost_packets"),
            packets = it.optLongOrNull("packets"),
            peakMbps = it.optDoubleOrNull("peak_mbps"), minMbps = it.optDoubleOrNull("min_mbps"),
        )
    }

    fun network(n: NetworkSnapshot) = JSONObject()
        .put("type", n.type.name).putOpt("ssid", n.ssid).putOpt("frequency_mhz", n.frequencyMhz)
        .putOpt("band", n.band).putOpt("rssi_dbm", n.rssiDbm).putOpt("link_mbps", n.linkMbps)
        .putOpt("wifi_standard", n.wifiStandard).putOpt("cellular_type", n.cellularType)
        .putOpt("carrier", n.carrier).put("vpn", n.vpn)

    fun network(j: JSONObject?) = if (j == null) NetworkSnapshot() else NetworkSnapshot(
        type = enumOr(j.optString("type"), NetType.OTHER),
        ssid = j.optStringOrNull("ssid"), frequencyMhz = j.optIntOrNull("frequency_mhz"),
        rssiDbm = j.optIntOrNull("rssi_dbm"), linkMbps = j.optIntOrNull("link_mbps"),
        wifiStandard = j.optStringOrNull("wifi_standard"), cellularType = j.optStringOrNull("cellular_type"),
        carrier = j.optStringOrNull("carrier"), vpn = j.optBoolean("vpn", false),
    )

    fun record(r: TestRecord): JSONObject = JSONObject()
        .put("id", r.id).put("timestamp", r.timestamp).put("server_label", r.serverLabel)
        .put("options", options(r.options))
        .put("summary", JSONObject().put("upload", flow(r.summary.upload)).put("download", flow(r.summary.download))
            .putOpt("duration_s", r.summary.durationSec))
        .put("intervals", JSONArray().apply {
            r.points.forEach { p ->
                put(JSONObject().put("end_s", p.endSec).putOpt("up_mbps", p.upMbps).putOpt("down_mbps", p.downMbps)
                    .put("omitted", p.omitted))
            }
        })
        .put("network", network(r.network))
        .put("stopped_early", r.stoppedEarly)
        .putOpt("iperf_version", r.iperfVersion)
        .putOpt("error_title", r.errorTitle)
        .putOpt("error_detail", r.errorDetail)

    fun record(j: JSONObject): TestRecord {
        val s = j.optJSONObject("summary")
        val pts = j.optJSONArray("intervals")
        return TestRecord(
            id = j.getString("id"),
            timestamp = j.getLong("timestamp"),
            serverLabel = j.optString("server_label", ""),
            options = options(j.getJSONObject("options")),
            summary = Summary(
                upload = flow(s?.optJSONObject("upload")),
                download = flow(s?.optJSONObject("download")),
                durationSec = s?.optDoubleOrNull("duration_s"),
            ),
            points = (0 until (pts?.length() ?: 0)).mapNotNull { i ->
                pts!!.optJSONObject(i)?.let {
                    IntervalPoint(it.optDouble("end_s", 0.0), it.optDoubleOrNull("up_mbps"), it.optDoubleOrNull("down_mbps"), it.optBoolean("omitted"))
                }
            },
            network = network(j.optJSONObject("network")),
            stoppedEarly = j.optBoolean("stopped_early", false),
            iperfVersion = j.optStringOrNull("iperf_version"),
            errorTitle = j.optStringOrNull("error_title"),
            errorDetail = j.optStringOrNull("error_detail"),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
