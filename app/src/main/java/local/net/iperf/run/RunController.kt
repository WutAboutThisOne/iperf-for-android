package local.net.iperf.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.net.iperf.core.ErrorMapper
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.IperfAuth
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.RunError
import local.net.iperf.core.Summary
import local.net.iperf.core.enriched
import local.net.iperf.core.TestOptions
import local.net.iperf.core.PointBuffer
import local.net.iperf.data.TestRecord
import java.util.UUID

sealed interface RunState {
    data object Idle : RunState
    data class Running(
        val options: TestOptions,
        val serverLabel: String,
        val points: List<IntervalPoint> = emptyList(),
        val connected: Boolean = false,
        val stopping: Boolean = false,
        /** Seconds since the test began (from iperf3's own interval clock). */
        val elapsedSec: Double = points.lastOrNull()?.endSec ?: 0.0,
        /** Latest one-second reading, for the notification and the live readout. */
        val latest: IntervalPoint? = points.lastOrNull(),
    ) : RunState {
        val endless: Boolean get() = options.endless
        val progress: Float get() = if (endless) 0f else
            (elapsedSec / (options.durationSec + options.omitSec).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    }
    data class Done(val record: TestRecord) : RunState
    data class Failed(val error: RunError, val options: TestOptions, val serverLabel: String, val record: TestRecord) : RunState
}

/** Drives one iperf3 run at a time and turns its events into [RunState] + a saved [TestRecord]. */
class RunController(
    private val engine: IperfEngine,
    private val scope: CoroutineScope,
    private val networkProbe: suspend () -> NetworkSnapshot,
    private val onRecord: (TestRecord) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopRequested = false

    val isRunning: Boolean get() = _state.value is RunState.Running

    fun start(options: TestOptions, serverLabel: String, auth: IperfAuth? = null) {
        if (isRunning) return
        stopRequested = false
        _state.value = RunState.Running(options, serverLabel)
        val startedAt = clock()
        job = scope.launch {
            val network = runCatching { networkProbe() }.getOrDefault(NetworkSnapshot())
            // Bounded: an endless run keeps a downsampled whole-run graph plus a 5-minute full-resolution window.
            val buffer = PointBuffer()
            var summary: Summary? = null
            var error: String? = null
            var version: String? = null
            try {
                engine.run(options, auth).collect { ev ->
                    when (ev) {
                        is IperfEvent.Started -> {
                            version = ev.version
                            update { it.copy(connected = true) }
                        }
                        is IperfEvent.Interval -> {
                            buffer.add(ev.point)
                            val live = if (options.endless) buffer.window() else buffer.all()
                            update { it.copy(points = live, connected = true, elapsedSec = buffer.lastEndSec, latest = ev.point) }
                        }
                        is IperfEvent.Finished -> summary = ev.summary
                        is IperfEvent.Error -> if (error == null) error = ev.message
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (error == null) error = e.message ?: e.javaClass.simpleName
            }
            val stopped = stopRequested
            // Prefer iperf3's own end summary; fall back to whatever intervals arrived before a drop or stop.
            val points = buffer.all()
            val finalSummary = (summary ?: buffer.summary() ?: Summary()).enriched(points)
            val fail = when {
                error != null && !stopped -> ErrorMapper.map(error!!, options.host, options.port, usedLogin = auth != null)
                summary == null && !stopped && buffer.count == 0 ->
                    ErrorMapper.map("iperf3 finished without results", options.host, options.port)
                else -> null
            }
            val record = TestRecord(
                id = UUID.randomUUID().toString(),
                timestamp = startedAt,
                serverLabel = serverLabel,
                options = options,
                summary = finalSummary,
                points = points,
                network = network,
                stoppedEarly = stopped,
                iperfVersion = version,
                errorTitle = fail?.title,
                errorDetail = fail?.detail,
            )
            onRecord(record)
            _state.value = if (fail != null) RunState.Failed(fail, options, serverLabel, record) else RunState.Done(record)
        }
    }

    fun stop() {
        if (!isRunning) return
        stopRequested = true
        update { it.copy(stopping = true) }
        engine.stop()
    }

    fun dismiss() { if (!isRunning) _state.value = RunState.Idle }

    /** Test hook: drop any in-flight run so a later test starts from Idle. */
    fun resetForTests() {
        stopRequested = true
        job?.cancel(); job = null
        stopRequested = false
        _state.value = RunState.Idle
    }

    private inline fun update(f: (RunState.Running) -> RunState.Running) {
        val s = _state.value
        if (s is RunState.Running) _state.value = f(s)
    }
}
