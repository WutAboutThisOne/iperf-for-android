package local.net.iperf.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import local.net.iperf.AppContainer
import local.net.iperf.data.LogAction
import local.net.iperf.data.Server
import local.net.iperf.manager.ManagerApi
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.manager.ManagerException
import local.net.iperf.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Reservations made from the port prompt: timer, Extend, and release from elsewhere, in the ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReservationFlowTest {
    private lateinit var app: Application
    private val fake = ManagerControllerTest.FakeApi()
    private var refuseExtend = false
    private val api = object : ManagerApi by fake {
        var reserves = 0
        override suspend fun reserve(port: Int, minutes: Int) {
            reserves++
            if (refuseExtend && reserves > 1) throw ManagerException(ManagerException.Kind.CONFLICT, "already reserved")
            fake.reserve(port, minutes)
        }
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        AppContainer.get(app).foregroundServiceEnabled = false
    }

    private fun idle(cond: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + 5000
        do { ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS) } while (!cond() && System.currentTimeMillis() < end)
    }

    private fun reservedVm(): Pair<AppViewModel, Server> {
        val vm = AppViewModel(app)
        vm.apiFactory = { api }
        vm.saveServer(Server("", "iperf.example.net", 5201, "Lab", "https://iperf.example.net", managerEnabled = true))
        val server = vm.serverList.value.single()
        vm.choosePort(server, 5205, reserve = true)
        idle { vm.testUi.value.oneTime?.reserved == true }
        return vm to server
    }

    @Test fun timerAndExtend() {
        val (vm, server) = reservedVm()
        val ui = vm.testUi.value
        assertTrue(ui.canExtend)
        val left = ui.reservedUntilMs!! - System.currentTimeMillis()
        assertTrue("about 15 minutes left: $left", left in 14 * 60_000L..15 * 60_000L)
        vm.extendOneTime()
        idle { fake.calls.count { it == "reserve 5205 15" } == 2 }
        idle()
        assertEquals(2, fake.calls.count { it == "reserve 5205 15" })
        assertEquals(LogAction.EXTEND, vm.activityLog.forServer(server.id).first().action)
    }

    @Test fun refusedExtendIsHiddenAndRemembered() {
        refuseExtend = true
        val (vm, server) = reservedVm()
        vm.extendOneTime()
        idle { !vm.testUi.value.canExtend }
        assertFalse(vm.testUi.value.canExtend)
        assertTrue(vm.notice.value!!.contains("Extend is now hidden"))
        assertTrue("remembered for next time", server.id in local.net.iperf.data.Prefs(app).extendUnsupported)
    }

    @Test fun releasedElsewhereClearsTheTestTab() {
        val (vm, server) = reservedVm()
        // e.g. Release all mine on the Manager screen, or the notification.
        AppContainer.get(app).reservations.remove(server.id, 5205)
        idle { vm.testUi.value.oneTime == null }
        assertNull(vm.testUi.value.oneTime)
    }

    @Test fun releaseAllMineFromTheManager() {
        val (vm, server) = reservedVm()
        vm.openManager(server)
        idle { vm.manager.value?.state?.value?.loaded == true }
        assertEquals(setOf(5205), vm.manager.value!!.state.value.mine.keys)
        vm.manager.value!!.releaseAllMine()
        idle { vm.testUi.value.oneTime == null }
        assertTrue(fake.calls.contains("release 5205"))
        assertNull(vm.testUi.value.oneTime)
        assertEquals(LogAction.RELEASE_ALL, vm.activityLog.forServer(server.id).first().action)
    }
}
