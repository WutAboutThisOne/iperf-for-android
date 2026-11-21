package local.net.iperf.service

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import local.net.iperf.AppContainer
import local.net.iperf.core.Direction
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.IperfAuth
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.TestOptions
import local.net.iperf.run.IperfEngine
import local.net.iperf.run.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestRunServiceTest {
    class ChannelEngine : IperfEngine {
        val ch = Channel<IperfEvent>(Channel.UNLIMITED)
        var stops = 0
        override fun run(options: TestOptions, auth: IperfAuth?): Flow<IperfEvent> = ch.consumeAsFlow()
        override fun stop() { stops++; ch.close() }
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) { Thread.sleep(10); ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private val endless = TestOptions("192.0.2.10", 5201, direction = Direction.BIDIR, durationSec = 0)

    @Test fun notificationTextShowsRateAndElapsed() {
        val r = RunState.Running(endless, "Lab", points = listOf(IntervalPoint(83.0, upMbps = 512.0, downMbps = 88.4)), connected = true)
        assertEquals("↑ 512 Mbps · ↓ 88.4 Mbps", TestRunService.rateText(r))
        assertEquals("1:23 elapsed · endless", TestRunService.timeText(r))
        val timed = RunState.Running(TestOptions("h", durationSec = 30, direction = Direction.DOWNLOAD), "Lab",
            points = listOf(IntervalPoint(12.0, downMbps = 940.0)), connected = true)
        assertEquals("↓ 940 Mbps", TestRunService.rateText(timed))
        assertEquals("0:12 of 0:30", TestRunService.timeText(timed))
        assertEquals("Connecting…", TestRunService.rateText(RunState.Running(endless, "Lab")))
    }

    @Test fun runStartsForegroundServiceAndStopActionStopsTheTest() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer.get(app)
        val engine = ChannelEngine()
        c.engine.delegate = engine
        c.runner.start(endless, "Lab")
        ShadowLooper.idleMainLooper()
        // The container asked Android to start the foreground service.
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TestRunService::class.java.name, started.component!!.className)

        engine.ch.trySend(IperfEvent.Interval(IntervalPoint(5.0, upMbps = 300.0, downMbps = 90.0)))
        waitUntil { (c.runner.state.value as? RunState.Running)?.latest != null }
        val controller = Robolectric.buildService(TestRunService::class.java, started).create().startCommand(0, 1)
        val service = controller.get()
        val n: Notification = shadowOf(service).lastForegroundNotification
        assertNotNull(n)
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("text=$text state=${c.runner.state.value}", text.contains("↑ 300 Mbps"))
        assertEquals("Stop", n.actions.single().title.toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // App in background: the test keeps running; the notification's Stop goes to the service.
        controller.withIntent(Intent(app, TestRunService::class.java).setAction(TestRunService.ACTION_STOP)).startCommand(0, 2)
        ShadowLooper.idleMainLooper()
        assertEquals(1, engine.stops)
        waitUntil { c.runner.state.value is RunState.Done }
        val st = c.runner.state.value
        assertTrue("state $st", st is RunState.Done)
        assertTrue((st as RunState.Done).record.stoppedEarly)
        // Test over → service leaves the foreground and stops.
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(c.runner.isRunning)
    }
}
