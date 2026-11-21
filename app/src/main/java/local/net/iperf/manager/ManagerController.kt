package local.net.iperf.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.net.iperf.data.LogAction
import local.net.iperf.data.InMemoryReservations
import local.net.iperf.data.ReservationTracker

enum class InstanceAction(val label: String, val past: String) {
    START("Start", "started"), STOP("Stop", "stopped"), RESTART("Restart", "restarted"),
    RESERVE("Reserve", "reserved"), RELEASE("Release", "released"),
}

data class LogUi(
    val port: Int,
    val lines: List<String> = emptyList(),
    val next: Int? = null,
    val total: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val auto: Boolean = true,
)

data class ManagerUi(
    val serverTitle: String = "",
    val baseUrl: String = "",
    val loading: Boolean = false,
    val instances: List<ManagedInstance> = emptyList(),
    val ports: PortAvailability? = null,
    val error: String? = null,
    val busy: Set<Int> = emptySet(),
    val bulkBusy: Boolean = false,
    val message: String? = null,
    val logs: LogUi? = null,
    val loaded: Boolean = false,
    /** Ports this phone reserved on this server → when that reservation ends (epoch ms). */
    val mine: Map<Int, Long> = emptyMap(),
    /** False once the manager has refused to extend a held reservation; Extend is then hidden. */
    val extendSupported: Boolean = true,
)

/** State for the server management screen: instances, actions, bulk start/stop, and an incremental log viewer. */
class ManagerController(
    private val api: ManagerApi,
    private val scope: CoroutineScope,
    serverTitle: String,
    baseUrl: String,
    private val logPollMs: Long = 3000,
    private val maxLogLines: Int = 2000,
    private val activity: ActivityLogger = ActivityLogger { _, _, _, _, _ -> },
    private val serverId: String = "",
    private val tracker: ReservationTracker = InMemoryReservations(),
    extendSupported: Boolean = true,
    /** Called once when the manager refuses Extend, so the choice is remembered for this server. */
    private val onExtendUnsupported: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ManagerUi(serverTitle, baseUrl, extendSupported = extendSupported))
    val state: StateFlow<ManagerUi> = _state.asStateFlow()
    private var logJob: Job? = null

    init { syncMine() }

    /** Re-reads this server's reservations from the tracker (they can also change from the Test tab or notification). */
    fun syncMine() = _state.update { s -> s.copy(mine = tracker.forServer(serverId, clock()).associate { it.port to it.expiresAtMs }) }

    private fun errText(e: Throwable) = (e as? ManagerException)?.message ?: (e.message ?: "Something went wrong")

    fun refresh(): Job = scope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val inst = api.instances()
            val ports = runCatching { api.availablePorts() }.getOrNull()
            _state.update { it.copy(loading = false, instances = inst, ports = ports ?: it.ports, loaded = true) }
            syncMine()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(loading = false, error = errText(e), loaded = true) }
        }
    }

    fun act(port: Int, action: InstanceAction, minutes: Int = ManagerClient.DEFAULT_RESERVE_MINUTES): Job = scope.launch {
        _state.update { it.copy(busy = it.busy + port, message = null) }
        val logAction = when (action) {
            InstanceAction.START -> LogAction.START
            InstanceAction.STOP -> LogAction.STOP
            InstanceAction.RESTART -> LogAction.RESTART
            InstanceAction.RESERVE -> LogAction.RESERVE
            InstanceAction.RELEASE -> LogAction.RELEASE
        }
        val msg = try {
            when (action) {
                InstanceAction.START -> api.start(port)
                InstanceAction.STOP -> api.stop(port)
                InstanceAction.RESTART -> api.restart(port)
                InstanceAction.RESERVE -> api.reserve(port, minutes)
                InstanceAction.RELEASE -> api.release(port)
            }
            when (action) {
                InstanceAction.RESERVE -> tracker.put(serverId, port, minutes, clock())
                InstanceAction.RELEASE -> tracker.remove(serverId, port)
                else -> Unit
            }
            syncMine()
            activity.log(logAction, listOf(port), true,
                if (action == InstanceAction.RESERVE) "Reserved for $minutes min" else action.past.replaceFirstChar { it.uppercase() }, false)
            "Port $port ${action.past}"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            activity.log(logAction, listOf(port), false, errText(e), false)
            "${action.label} $port failed: ${errText(e)}"
        }
        _state.update { it.copy(busy = it.busy - port, message = msg) }
        refresh().join()
    }

    /**
     * Extend = reserve the port again for [minutes]. If the manager refuses because the port is already held (409),
     * Extend is hidden from then on and the user is told to release and reserve instead.
     */
    fun extend(port: Int, minutes: Int = ManagerClient.DEFAULT_RESERVE_MINUTES): Job = scope.launch {
        _state.update { it.copy(busy = it.busy + port, message = null) }
        val msg = try {
            api.reserve(port, minutes)
            tracker.put(serverId, port, minutes, clock())
            syncMine()
            val until = localTime((clock() + minutes * 60_000L).toString())
            activity.log(LogAction.EXTEND, listOf(port), true, "Extended by $minutes min (until $until)", false)
            "Port $port reserved until $until"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            activity.log(LogAction.EXTEND, listOf(port), false, errText(e), false)
            if ((e as? ManagerException)?.kind == ManagerException.Kind.CONFLICT) {
                _state.update { it.copy(extendSupported = false) }
                onExtendUnsupported()
                "This manager won't extend a reservation that's still held, so Extend is now hidden for it. " +
                    "Release the port and reserve it again instead."
            } else "Extend $port failed: ${errText(e)}"
        }
        _state.update { it.copy(busy = it.busy - port, message = msg) }
        refresh().join()
    }

    /** Releases every port this phone reserved on this server (the UI confirms first). Logged as one entry. */
    fun releaseAllMine(): Job = scope.launch {
        val ports = tracker.forServer(serverId, clock()).map { it.port }
        if (ports.isEmpty()) { _state.update { it.copy(message = "No ports reserved from this phone") }; return@launch }
        _state.update { it.copy(bulkBusy = true, busy = it.busy + ports, message = null) }
        val ok = mutableListOf<Int>(); val failed = linkedMapOf<Int, String>()
        for (p in ports) {
            try {
                api.release(p); ok += p; tracker.remove(serverId, p)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Already gone on the manager (expired or released elsewhere): forget it here too.
                if ((e as? ManagerException)?.kind == ManagerException.Kind.NOT_FOUND) { tracker.remove(serverId, p); ok += p }
                else failed[p] = errText(e)
            }
        }
        syncMine()
        val text = buildString {
            append(if (ok.isEmpty()) "Released none" else "Released " + ok.joinToString(", "))
            if (failed.isNotEmpty()) append("; failed " + failed.entries.joinToString { "${it.key}: ${it.value}" })
        }
        activity.log(LogAction.RELEASE_ALL, ports, failed.isEmpty(), text, false)
        _state.update { it.copy(bulkBusy = false, busy = it.busy - ports.toSet(), message = text) }
        refresh().join()
    }

    fun startAll(): Job = bulk(true)
    fun stopAll(): Job = bulk(false)

    private fun bulk(start: Boolean): Job = scope.launch {
        _state.update { it.copy(bulkBusy = true, message = null) }
        val logAction = if (start) LogAction.START_ALL else LogAction.STOP_ALL
        val msg = try {
            val r = if (start) api.startAll() else api.stopAll()
            val text = r.describe(if (start) "started" else "stopped")
            activity.log(logAction, (r.ok + r.errors.keys).sorted(), r.errors.isEmpty(), text, false)
            text
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            activity.log(logAction, emptyList(), false, errText(e), false)
            (if (start) "Start all failed: " else "Stop all failed: ") + errText(e)
        }
        _state.update { it.copy(bulkBusy = false, message = msg) }
        refresh().join()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun openLogs(port: Int) {
        logJob?.cancel()
        _state.update { it.copy(logs = LogUi(port)) }
        logJob = scope.launch {
            loadLogs(full = true)
            while (isActive) {
                delay(logPollMs)
                if (_state.value.logs?.auto == true) loadLogs(full = false)
            }
        }
    }

    fun setAutoRefresh(on: Boolean) = _state.update { s -> s.copy(logs = s.logs?.copy(auto = on)) }

    fun refreshLogs(): Job = scope.launch { loadLogs(full = false) }

    fun closeLogs() { logJob?.cancel(); logJob = null; _state.update { it.copy(logs = null) } }

    /** Incremental: asks for ?since=<next>; if the server's cursor went backwards (log rotated), reloads the window. */
    internal suspend fun loadLogs(full: Boolean) {
        val cur = _state.value.logs ?: return
        val since = if (full) null else cur.next
        _state.update { s -> s.copy(logs = s.logs?.copy(loading = true, error = null)) }
        try {
            val w = api.logs(cur.port, since)
            if (since != null && w.total != null && w.total < since) return loadLogs(full = true)
            _state.update { s ->
                val l = s.logs ?: return@update s
                if (l.port != cur.port) return@update s
                val merged = if (since == null) w.lines else l.lines + w.lines
                s.copy(logs = l.copy(lines = merged.takeLast(maxLogLines), next = w.next ?: (since ?: 0) + w.lines.size,
                    total = w.total, loading = false))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { s -> s.copy(logs = s.logs?.copy(loading = false, error = errText(e))) }
        }
    }
}
