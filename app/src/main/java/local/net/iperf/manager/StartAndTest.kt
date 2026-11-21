package local.net.iperf.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.net.iperf.data.LogAction
import java.net.InetSocketAddress
import java.net.Socket

/** Records a manager action. Implementations must never put credentials in [detail]. */
fun interface ActivityLogger {
    fun log(action: LogAction, ports: List<Int>, ok: Boolean, detail: String?, startAndTest: Boolean)
}

/** Progress of a Start & test, for the manager screen. */
data class StartTestProgress(val port: Int, val step: String, val error: String? = null) {
    val running: Boolean get() = error == null
}

sealed interface StartTestResult {
    /** Port is up, reachable and reserved. [startedByUs] = we started it, so we may offer to stop it afterwards. */
    data class Ready(val port: Int, val startedByUs: Boolean) : StartTestResult
    data class Failed(val port: Int, val message: String) : StartTestResult
}

/** Default reachability check: a plain TCP connect to the iperf3 port (closed immediately). */
suspend fun tcpReachable(host: String, port: Int, timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
    runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }; true }.getOrDefault(false)
}

/**
 * Start & test, minus the test itself:
 *  1. POST start (skipped when the instance is already running),
 *  2. reserve the port for [minutes] (so nobody else grabs it during the test).
 *     Start is first because some managers refuse POST /start while the port is reserved,
 *     even by the same login that just reserved it.
 *  3. poll a TCP connect (and skip if the manager marks the port blocked) until the port answers, up to [timeoutMs].
 *     A successful connect is enough even if the manager still reports STOPPED.
 * Every step is logged. If start or reserve fails, or the port never answers, an instance we started is stopped
 * and a reservation we made is released, so a failed attempt leaves the server as it was.
 */
class StartAndTest(
    private val api: ManagerApi,
    private val log: ActivityLogger,
    private val host: String,
    private val reachable: suspend (String, Int) -> Boolean = { h, p -> tcpReachable(h, p) },
    private val timeoutMs: Long = 20_000,
    private val pollMs: Long = 1_000,
    private val minutes: Int = ManagerClient.DEFAULT_RESERVE_MINUTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun err(e: Throwable) = (e as? ManagerException)?.message ?: e.message ?: "Something went wrong"

    suspend fun prepare(port: Int, alreadyRunning: Boolean, onStep: (String) -> Unit = {}): StartTestResult {
        var startedByUs = false
        if (!alreadyRunning) {
            onStep("Starting port $port…")
            try {
                api.start(port)
                startedByUs = true
                log.log(LogAction.START, listOf(port), true, "Started", true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.log(LogAction.START, listOf(port), false, err(e), true)
                return StartTestResult.Failed(port, "Couldn't start port $port: ${err(e)}")
            }
        }

        onStep("Reserving port $port…")
        try {
            api.reserve(port, minutes)
            log.log(LogAction.RESERVE, listOf(port), true, "Reserved for $minutes min", true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.log(LogAction.RESERVE, listOf(port), false, err(e), true)
            val stoppedAgain = rollback(port, stop = startedByUs, release = false)
            val why = "Couldn't reserve port $port: ${err(e)}"
            val extra = when {
                stoppedAgain -> "The instance was stopped again."
                startedByUs -> "A stop was requested."
                else -> null
            }
            return StartTestResult.Failed(port, if (extra == null) why
                else if (why.endsWith('.')) "$why $extra" else "$why. $extra")
        }

        onStep("Waiting for $host:$port to accept connections…")
        val began = now()
        var up = false
        var blocked: Boolean
        while (true) {
            val status = runCatching { api.instances().firstOrNull { it.port == port }?.status }.getOrNull()
            blocked = status == InstanceStatus.BLOCKED
            // A successful TCP connect means the iperf3 port is up, even if the manager still
            // reports STOPPED (start can be accepted before the status field flips).
            if (!blocked && reachable(host, port)) { up = true; break }
            if (now() - began >= timeoutMs) break
            delay(pollMs)
        }
        val waited = (now() - began) / 1000
        if (!up) {
            val why = if (blocked) "blocked by the manager" else "didn't accept connections at $host:$port"
            log.log(LogAction.WAIT_REACHABLE, listOf(port), false, "Not reachable after ${timeoutMs / 1000} s ($why)", true)
            val stoppedAgain = rollback(port, stop = startedByUs, release = true)
            return StartTestResult.Failed(port,
                "Port $port $why within ${timeoutMs / 1000} s" +
                    (if (stoppedAgain) ", so it was stopped again" else if (startedByUs) ", and a stop was requested" else "") +
                    " and the reservation was released.")
        }
        log.log(LogAction.WAIT_REACHABLE, listOf(port), true, "Reachable at $host:$port after $waited s", true)
        return StartTestResult.Ready(port, startedByUs)
    }

    /** Rolls back the reservation, and stops an instance we started. Stop first so a manager that
     *  requires a reservation to accept stop still sees one. Returns true if a requested stop succeeded. */
    private suspend fun rollback(port: Int, stop: Boolean, release: Boolean = true): Boolean {
        var stoppedOk = false
        if (stop) try {
            api.stop(port)
            stoppedOk = true
            log.log(LogAction.STOP, listOf(port), true, "Rolled back", true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.log(LogAction.STOP, listOf(port), false, err(e), true)
        }
        if (release) try {
            api.release(port)
            log.log(LogAction.RELEASE, listOf(port), true, "Rolled back", true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.log(LogAction.RELEASE, listOf(port), false, err(e), true)
        }
        return stoppedOk
    }
}
