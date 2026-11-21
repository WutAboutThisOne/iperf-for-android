package local.net.iperf

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.net.iperf.data.ActivityLogStore
import local.net.iperf.data.HistoryStore
import local.net.iperf.data.KeystoreCipher
import local.net.iperf.data.Prefs
import local.net.iperf.data.SecretStore
import local.net.iperf.data.ServerStore
import local.net.iperf.net.NetworkProbe
import local.net.iperf.run.ProcessIperfEngine
import local.net.iperf.run.SwitchableEngine
import local.net.iperf.run.RunController
import local.net.iperf.run.RunState
import local.net.iperf.service.TestRunService
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import local.net.iperf.data.ActivityEntries
import local.net.iperf.data.LogAction
import local.net.iperf.data.ReservationStore
import local.net.iperf.data.Server
import local.net.iperf.manager.ActivityLogger
import local.net.iperf.manager.ManagerApi
import local.net.iperf.manager.ManagerClient

/** A port this phone reserved on a Manager for the current run, released when the run ends. */
data class RunReservation(val serverId: String, val port: Int)

/**
 * Process-wide objects. The test runner lives here (not in the ViewModel) so a test keeps going, and can be
 * stopped from its notification, while the app is in the background or its screen has been closed.
 */
class AppContainer private constructor(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val servers = ServerStore(File(app.filesDir, "servers.json"))
    val secrets = SecretStore(File(app.noBackupFilesDir, "secrets.json"), KeystoreCipher())
    val history = HistoryStore(File(app.filesDir, "history.json"))
    val activityLog = ActivityLogStore(File(app.filesDir, "activity-log.json"))
    val prefs = Prefs(app)
    val probe = NetworkProbe(app)
    val engine = SwitchableEngine(ProcessIperfEngine(
        binary = File(app.applicationInfo.nativeLibraryDir, "libiperf3.so"),
        tmpDir = File(app.cacheDir, "iperf-tmp"),
        keyDir = File(app.noBackupFilesDir, "iperf-keys"),
    ))
    val runner = RunController(engine, scope, { probe.snapshot() }, { history.add(it) })
    /** Ports reserved from this phone (0.1.4). */
    val reservations = ReservationStore(File(app.filesDir, "reservations.json"))
    /** The reserved port the running test uses, if this app reserved it: the notification offers Release for it. */
    val runReservation = MutableStateFlow<RunReservation?>(null)
    /** One-line results of actions taken outside the UI (the notification's Release), shown as a snackbar. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Null when the server has no manager configured or its URL is unusable. Overridable in tests (a mock manager). */
    var managerApiFactory: (Server) -> ManagerApi? = { server ->
        server.managerBaseUrl?.let { base -> runCatching { ManagerClient(base, secrets.get(server.id).managerLogin) }.getOrNull() }
    }

    fun logger(server: Server): ActivityLogger = ActivityLogger { action, ports, ok, detail, sat ->
        activityLog.add(ActivityEntries.of(server, secrets.get(server.id), action, ports, ok, detail, sat))
    }

    /** Release from the notification: frees the port for others; the test itself keeps running. */
    fun releaseRunReservation(): Job? {
        val rr = runReservation.value ?: return null
        val server = servers.find(rr.serverId) ?: return null
        val api = managerApiFactory(server) ?: return null
        return scope.launch {
            try {
                api.release(rr.port)
                reservations.remove(rr.serverId, rr.port)
                if (runReservation.value == rr) runReservation.value = null
                logger(server).log(LogAction.RELEASE, listOf(rr.port), true, "Released from the notification; the test kept running", false)
                notices.tryEmit("Released port ${rr.port}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger(server).log(LogAction.RELEASE, listOf(rr.port), false, e.message, false)
                notices.tryEmit("Couldn't release port ${rr.port}: ${e.message}")
            }
        }
    }

    /** Set false in tests that don't want the real service started. */
    var foregroundServiceEnabled = true

    init {
        scope.launch {
            var wasRunning = false
            runner.state.collect { st ->
                val running = st is RunState.Running
                if (running && !wasRunning && foregroundServiceEnabled) startService()
                wasRunning = running
            }
        }
    }

    private fun startService() {
        // Starting from the foreground (the user just tapped Start). If Android refuses anyway, the test still runs.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, TestRunService::class.java)) }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(app: Application): AppContainer = synchronized(this) {
            instance?.takeIf { it.app === app } ?: AppContainer(app).also { fresh -> instance?.scope?.cancel(); instance = fresh }
        }
    }
}
