package local.net.iperf.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import local.net.iperf.AppContainer
import local.net.iperf.core.IperfAuth
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.TestOptions
import local.net.iperf.data.Server
import local.net.iperf.run.IperfEngine
import local.net.iperf.run.RunState
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.ui.AppViewModel
import local.net.iperf.ui.IperfApp
import local.net.iperf.ui.theme.IperfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Start & test through IperfApp: manager → Test tab, one-time port, optional auto-start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h1200dp-xhdpi")
class StartAndTestUiFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private val fake = ManagerControllerTest.FakeApi().apply {
        instances = listOf(ManagedInstance(5203, InstanceStatus.STOPPED, "stopped"))
    }
    private val api = object : local.net.iperf.manager.ManagerApi by fake {
        override suspend fun start(port: Int) {
            fake.start(port)
            fake.instances = listOf(ManagedInstance(port, InstanceStatus.RUNNING, "running"))
        }
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        AppContainer.get(app).apply {
            foregroundServiceEnabled = false
            runner.resetForTests()
        }
    }

    @Test fun fromServersTabLoadsOneTimePortAndLeavesManager() {
        val vm = AppViewModel(app)
        vm.apiFactory = { api }
        var probedHost: String? = null
        var probedPort: Int? = null
        vm.reachability = { h, p -> probedHost = h; probedPort = p; true }
        vm.setAutoStartTest(false)
        vm.saveServer(Server("", "192.0.2.10", 5201, "Lab", "https://iperf.example.com", managerEnabled = true))

        compose.setContent { IperfTheme { IperfApp(vm) } }
        compose.onNodeWithTag("tab_SERVERS").performClick()
        compose.onNodeWithContentDescription("Manage Lab").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start & test").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Port 5203 for this run", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Port 5203 for this run", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Saved port 5201 is unchanged").assertIsDisplayed()
        assertNull(vm.manager.value)
        assertEquals("192.0.2.10", probedHost)
        assertEquals(5203, probedPort)
        assertTrue(vm.goToTest.value == 0L) // consumed by the UI
    }


    @Test fun autoStartUsesManagedPortEvenIfAnotherServerWasSelected() {
        val engine = RecordingEngine()
        val vm = AppViewModel(app)
        AppContainer.get(app).engine.delegate = engine
        vm.apiFactory = { api }
        vm.reachability = { _, _ -> true }
        vm.setAutoStartTest(true)
        vm.onNotificationsAsked()
        vm.saveServer(Server("", "192.168.1.20", 5201, "Home NAS"))
        vm.saveServer(Server("", "192.0.2.10", 5201, "Lab", "https://iperf.example.com", managerEnabled = true))
        val home = vm.serverList.value.first { it.label == "Home NAS" }
        val lab = vm.serverList.value.first { it.label == "Lab" }
        vm.select(home)

        compose.setContent { IperfTheme { IperfApp(vm) } }
        vm.openManager(lab)
        compose.waitForIdle()
        compose.onNodeWithText("Start & test").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Port 5203 for this run", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Port 5203 for this run", substring = true).assertIsDisplayed()
        compose.waitUntil(10_000) {
            engine.started.isNotEmpty() || (vm.runState.value as? RunState.Running)?.options?.port == 5203
        }
        val started = engine.started.firstOrNull() ?: (vm.runState.value as RunState.Running).options
        assertEquals("192.0.2.10", started.host)
        assertEquals(5203, started.port)
    }
}

class RecordingEngine : IperfEngine {
    val started = mutableListOf<TestOptions>()
    val ch = Channel<IperfEvent>(Channel.UNLIMITED)
    override fun run(options: TestOptions, auth: IperfAuth?): Flow<IperfEvent> {
        started += options
        return ch.consumeAsFlow()
    }
    override fun stop() { ch.close() }
}
