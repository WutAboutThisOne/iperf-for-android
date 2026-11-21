package local.net.iperf.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import local.net.iperf.AppContainer
import local.net.iperf.RunReservation
import local.net.iperf.core.TestOptions
import local.net.iperf.data.LogAction
import local.net.iperf.data.Server
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.run.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationReleaseTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun running() = RunState.Running(TestOptions(host = "iperf.example.net", port = 5205), "Lab")

    @Test fun releaseButtonOnlyForOurReservation() {
        TestRunService.ensureChannel(app)
        val plain = TestRunService.build(app, running())
        assertEquals(listOf("Stop"), plain.actions.map { it.title.toString() })
        val ours = TestRunService.build(app, running(), releasePort = 5205)
        assertEquals(listOf("Stop", "Release port 5205"), ours.actions.map { it.title.toString() })
    }

    @Test fun releaseFromNotificationFreesThePortAndLogs() {
        val c = AppContainer.get(app)
        val fake = ManagerControllerTest.FakeApi()
        c.managerApiFactory = { fake }
        val server = c.servers.upsert(Server("", "iperf.example.net", 5201, "Lab", "https://iperf.example.net", managerEnabled = true))
        c.reservations.put(server.id, 5205, 15)
        c.runReservation.value = RunReservation(server.id, 5205)
        c.releaseRunReservation()
        val end = System.currentTimeMillis() + 3000
        while (c.runReservation.value != null && System.currentTimeMillis() < end) ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertTrue(fake.calls.contains("release 5205"))
        assertNull(c.runReservation.value)
        assertTrue(c.reservations.forServer(server.id).isEmpty())
        val entry = c.activityLog.forServer(server.id).first()
        assertEquals(LogAction.RELEASE, entry.action)
        assertTrue(entry.ok)
        assertTrue(entry.detail!!.contains("notification"))
    }
}
