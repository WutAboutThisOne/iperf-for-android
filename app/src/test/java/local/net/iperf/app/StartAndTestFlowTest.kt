package local.net.iperf.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import local.net.iperf.AppContainer
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.IperfEvent
import local.net.iperf.data.LogAction
import local.net.iperf.data.Server
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.run.RunState
import local.net.iperf.service.TestRunServiceTest
import local.net.iperf.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** End-to-end Start & test in the ViewModel, with a mock manager and a fake iperf3 engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartAndTestFlowTest {
    private lateinit var app: Application
    private val engine = TestRunServiceTest.ChannelEngine()
    private val fake = ManagerControllerTest.FakeApi().apply {
        instances = listOf(ManagedInstance(5203, InstanceStatus.STOPPED, "stopped"))
    }
    /** Starting 5203 makes it report "running", like the real manager. */
    private val api = object : local.net.iperf.manager.ManagerApi by fake {
        val calls get() = fake.calls
        override suspend fun start(port: Int) { fake.start(port); fake.instances = listOf(ManagedInstance(port, InstanceStatus.RUNNING, "running")) }
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        AppContainer.get(app).apply { engine.delegate = this@StartAndTestFlowTest.engine; foregroundServiceEnabled = false }
    }

    private fun idle(cond: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + 5000
        do { ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS) } while (!cond() && System.currentTimeMillis() < end)
    }

    @Test fun startAndTestLoadsPortRunsReleasesAndOffersStop() {
        val vm = AppViewModel(app)
        vm.apiFactory = { api }
        vm.reachability = { _, _ -> true }
        vm.saveServer(Server("", "iperf.example.net", 5201, "Lab", "https://iperf.example.net", managerEnabled = true))
        val server = vm.serverList.value.single()
        vm.openManager(server)
        idle { vm.manager.value?.state?.value?.loaded == true }
        api.calls.clear()

        vm.startAndTest(ManagedInstance(5203, InstanceStatus.STOPPED, "stopped"))
        idle { vm.goToTest.value > 0 }
        assertEquals(listOf("start 5203", "reserve 5203 15"), api.calls.take(2))
        assertNull("manager screen closed", vm.manager.value)
        assertTrue(vm.goToTest.value > 0)
        assertTrue("auto-start is on by default", vm.autoStartRequest.value > 0)
        val ui = runBlocking { vm.testUi.first { it.oneTime != null } }
        assertEquals(5203, ui.oneTime!!.port)
        assertTrue(ui.oneTime!!.reserved)
        assertEquals(server.id, ui.selected!!.id)
        assertEquals(5201, vm.serverList.value.single().port) // saved port untouched

        // The UI would call start() for the auto-start request.
        vm.consumeAutoStart(); vm.start()
        idle { vm.runState.value is RunState.Running }
        assertEquals(5203, (vm.runState.value as RunState.Running).options.port)
        engine.ch.trySend(IperfEvent.Interval(IntervalPoint(1.0, upMbps = 420.0)))
        vm.stop()
        idle { vm.stopOffer.value != null }
        assertTrue(vm.runState.value is RunState.Done)
        assertTrue(api.calls.contains("release 5203"))
        assertNotNull(vm.stopOffer.value)
        assertEquals(5203, vm.stopOffer.value!!.port)
        vm.stopOfferedInstance()
        idle { api.calls.contains("stop 5203") }
        idle()

        val log = vm.activityLog.forServer(server.id).reversed() // oldest first
        assertEquals(listOf(LogAction.START, LogAction.RESERVE, LogAction.WAIT_REACHABLE, LogAction.LOAD_PORT,
            LogAction.TEST_START, LogAction.TEST_END, LogAction.RELEASE, LogAction.STOP), log.map { it.action })
        assertTrue(log.all { it.startAndTest && it.ok && it.ports == listOf(5203) })
        assertTrue(log.first { it.action == LogAction.TEST_END }.detail!!.startsWith("Stopped"))
    }

    @Test fun autoStartToggleIsRemembered() {
        val vm = AppViewModel(app)
        assertTrue(vm.autoStartTest.value)
        vm.setAutoStartTest(false)
        assertEquals(false, AppViewModel(app).autoStartTest.value)
    }
}
