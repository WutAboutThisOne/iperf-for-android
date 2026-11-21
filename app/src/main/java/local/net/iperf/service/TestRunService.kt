package local.net.iperf.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.net.iperf.AppContainer
import local.net.iperf.MainActivity
import local.net.iperf.R
import local.net.iperf.core.Direction
import local.net.iperf.core.formatMbps
import local.net.iperf.run.RunState
import local.net.iperf.ui.test.formatElapsed

/**
 * Foreground service that exists only while a test runs: keeps the process (and the iperf3 child process) alive in
 * the background, shows the live rate + elapsed time, and offers Stop. It never starts a test by itself.
 */
class TestRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = AppContainer.get(application)
        if (intent?.action == ACTION_STOP) {
            container.runner.stop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELEASE) {
            container.releaseRunReservation()
            return START_NOT_STICKY
        }
        val st = container.runner.state.value
        if (st !is RunState.Running) { stopSelf(); return START_NOT_STICKY }
        ensureChannel(this)
        val n = build(this, st, container.runReservation.value?.port)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException: the test still runs, just without the service.
            stopSelf(); return START_NOT_STICKY
        }
        acquireWakeLock(st)
        if (watch == null) watch = scope.launch {
            kotlinx.coroutines.flow.combine(container.runner.state, container.runReservation) { s, rr -> s to rr }.collect { (s, rr) ->
                if (s is RunState.Running) post(s, rr?.port, force = rr?.port != postedRelease) else finish()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // notify() is a no-op without POST_NOTIFICATIONS; the service still runs.
    private var postedRelease: Int? = null

    private fun post(s: RunState.Running, releasePort: Int?, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < 900 && !s.stopping && !force) return
        lastPostedAt = now
        postedRelease = releasePort
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(this, s, releasePort)) }
    }

    private fun finish() {
        watch?.cancel(); watch = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Keeps the CPU awake (screen may be off) for the length of the test only. */
    private fun acquireWakeLock(s: RunState.Running) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val ms = if (s.endless) 24L * 3600_000 else (s.options.durationSec + s.options.omitSec + 120L) * 1000
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IperfForAndroid:test").apply {
            setReferenceCounted(false); acquire(ms)
        }
    }

    private fun releaseWakeLock() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ time limit for some service types: stop the test cleanly so its result is saved.
        AppContainer.get(application).runner.stop()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "test_running"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "local.net.iperf.action.STOP_TEST"
        const val ACTION_RELEASE = "local.net.iperf.action.RELEASE_PORT"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Running test", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while an iperf3 test runs, with its live rate and a Stop button"
                    setShowBadge(false)
                }
            )
        }

        /** "↑ 512 Mbps · ↓ 88.4 Mbps" for the directions being tested. */
        fun rateText(s: RunState.Running): String {
            val p = s.latest ?: return if (s.connected) "Measuring…" else "Connecting…"
            return buildList {
                if (s.options.direction != Direction.DOWNLOAD) add("↑ " + (p.upMbps?.let(::formatMbps) ?: "–"))
                if (s.options.direction != Direction.UPLOAD) add("↓ " + (p.downMbps?.let(::formatMbps) ?: "–"))
            }.joinToString(" · ")
        }

        fun timeText(s: RunState.Running): String =
            if (s.endless) "${formatElapsed(s.elapsedSec)} elapsed · endless"
            else "${formatElapsed(s.elapsedSec)} of ${formatElapsed((s.options.durationSec + s.options.omitSec).toDouble())}"

        /** [releasePort]: the test's port when this app reserved it; adds a Release button (the test keeps running). */
        fun build(context: Context, s: RunState.Running, releasePort: Int? = null): Notification {
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(context, 1,
                Intent(context, TestRunService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_test)
                .setContentTitle(if (s.stopping) "Stopping test…" else "iperf3 test · ${s.serverLabel}")
                .setContentText("${rateText(s)}  ·  ${timeText(s)}")
                .setSubText(s.options.describe())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
            if (!s.stopping) b.addAction(0, "Stop", stop)
            if (releasePort != null && !s.stopping) b.addAction(0, "Release port $releasePort", PendingIntent.getService(context, 2,
                Intent(context, TestRunService::class.java).setAction(ACTION_RELEASE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (!s.endless) b.setProgress(1000, (s.progress * 1000).toInt(), !s.connected)
            return b.build()
        }
    }
}
