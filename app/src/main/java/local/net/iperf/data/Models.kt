package local.net.iperf.data

import local.net.iperf.core.DEFAULT_PORT
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.Summary
import local.net.iperf.core.TestOptions

data class Server(
    val id: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val label: String = "",
    /** Optional server webpage (normalised, https:// added when no scheme was typed). */
    val webUrl: String = "",
    /** Optional iperf3 Manager HTTP API (e.g. https://iperf.example.net). */
    val managerEnabled: Boolean = false,
    /** Manager base URL; blank = use [webUrl]. */
    val managerUrl: String = "",
) {
    val title: String get() = label.ifBlank { host }
    val managerBaseUrl: String? get() = if (!managerEnabled) null else managerUrl.ifBlank { webUrl }.ifBlank { null }
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** A finished (or stopped) test as stored in history. */
data class TestRecord(
    val id: String,
    val timestamp: Long,
    val serverLabel: String,
    val options: TestOptions,
    val summary: Summary,
    val points: List<IntervalPoint>,
    val network: NetworkSnapshot,
    val stoppedEarly: Boolean = false,
    val iperfVersion: String? = null,
    /** Set when the run ended with an error (lost connection, busy server, etc.). */
    val errorTitle: String? = null,
    val errorDetail: String? = null,
) {
    val failed: Boolean get() = errorTitle != null
}
