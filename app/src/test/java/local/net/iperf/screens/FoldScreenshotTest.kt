package local.net.iperf.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import local.net.iperf.AppContainer
import local.net.iperf.SampleData
import local.net.iperf.core.Direction
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.OptionsDraft
import local.net.iperf.core.Protocol
import local.net.iperf.data.Server
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.LogWindow
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerApi
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.manager.PortAvailability
import local.net.iperf.manager.Reservation
import local.net.iperf.run.RunState
import local.net.iperf.service.TestRunServiceTest
import local.net.iperf.ui.AppViewModel
import local.net.iperf.ui.IperfApp
import local.net.iperf.ui.adaptive.Adaptive
import local.net.iperf.ui.adaptive.Hinge
import local.net.iperf.ui.adaptive.LocalAdaptive
import local.net.iperf.ui.theme.IperfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The real app UI (IperfApp + ViewModel) at the Galaxy Fold's unfolded size, ~841×701 dp at 420 dpi = 2208×1840 px.
 * EXAMPLE data from [SampleData] and a mock manager — not real measurements or Lab server.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w841dp-h701dp-land-420dpi")
abstract class FoldScreenshotBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }
    protected val engine = TestRunServiceTest.ChannelEngine()
    protected lateinit var vm: AppViewModel
    protected lateinit var c: AppContainer

    private val fake = ManagerControllerTest.FakeApi().apply {
        instances = (5201..5230).map { p ->
            when {
                p == 5205 -> ManagedInstance(p, InstanceStatus.RESERVED, "reserved", "running", "ok", Reservation(by = "example-phone"))
                p == 5212 || p == 5227 -> ManagedInstance(p, InstanceStatus.RESERVED, "reserved", "running", "ok", Reservation(by = "alex", minutes = 30))
                p % 4 == 0 -> ManagedInstance(p, InstanceStatus.RUNNING, "running", "running", "ok")
                p == 5219 -> ManagedInstance(p, InstanceStatus.BLOCKED, "blocked")
                else -> ManagedInstance(p, InstanceStatus.STOPPED, "stopped", "stopped")
            }
        }
    }
    private val api = object : ManagerApi by fake {
        override suspend fun availablePorts() = PortAvailability((5201..5230).filter { it != 5205 && it != 5212 && it != 5227 && it != 5219 }, listOf(5219))
        override suspend fun logs(port: Int, since: Int?) = if (since != null) LogWindow(emptyList(), since, 6) else LogWindow(listOf(
            "2026-11-19 19:58:02  Server listening on $port (test #12)",
            "2026-11-19 19:58:14  Accepted connection from 203.0.113.24, port 51022",
            "2026-11-19 19:58:14  [  5] local 192.0.2.10 port $port connected to 203.0.113.24 port 51024",
            "2026-11-19 19:58:24  [  5]   0.00-10.00  sec  1.09 GBytes   936 Mbits/sec  receiver",
            "2026-11-19 19:58:24  iperf Done.",
            "2026-11-19 19:58:24  Server listening on $port (test #13)",
        ), 6, 6)
    }

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        c = AppContainer.get(app)
        c.foregroundServiceEnabled = false
        c.engine.delegate = engine
        SampleData.history.forEach { c.history.add(it) }
        vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        vm.apiFactory = { api }
        SampleData.servers.forEach { vm.saveServer(it) }
        vm.saveServer(Server("lab", "iperf.example.com", 5201, "Lab iperf", "https://iperf.example.com", managerEnabled = true))
        vm.select(vm.serverList.value.first())
        vm.editDraft(OptionsDraft(Protocol.TCP, Direction.BIDIR, "30", "4", "100", "0"))
    }

    protected fun idle(ms: Long = 600) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    protected fun app(adaptive: Adaptive = Adaptive.of(841f), dark: Boolean = true) {
        compose.setContent { IperfTheme(dark = dark) { CompositionLocalProvider(LocalAdaptive provides adaptive) { IperfApp(vm) } } }
        idle()
    }

    protected fun capture(name: String) { idle(300); compose.onRoot().captureRoboImage(File(outDir, name).absolutePath) }

    protected fun startRunning(seconds: Int) {
        vm.start()
        idle()
        engine.ch.trySend(IperfEvent.Started("3.22", "192.168.1.20", 5201, "TCP", 4, 30))
        SampleData.wave(seconds, 580.0, 45.0).zip(SampleData.wave(seconds, 820.0, 60.0, 1.1)).forEachIndexed { i, (u, d) ->
            engine.ch.trySend(IperfEvent.Interval(IntervalPoint((i + 1).toDouble(), u, d)))
        }
        idle()
        check(vm.runState.value is RunState.Running)
    }

}

// One screenshot per class: Robolectric keeps the previous test's Compose root alive within a JVM (forkEvery = 1 is per class).
class FoldTestRunningShot : FoldScreenshotBase() {
    @Test fun foldTestRunning() {
        app()
        startRunning(18)
        capture("30-fold-test-running-dark.png")
    }
}

class FoldTestIdleShot : FoldScreenshotBase() {
    @Test fun foldTestIdle() {
        app(dark = false)
        capture("30b-fold-test-idle-light.png")
    }
}

class FoldHistoryShot : FoldScreenshotBase() {
    @Test fun foldHistoryListDetail() {
        app()
        compose.onNodeWithTag("rail_HISTORY").performClick()
        compose.onNodeWithTag("historyRow_${SampleData.history[2].id}").performClick()
        capture("31-fold-history-list-detail-dark.png")
    }
}

class FoldManagerShot : FoldScreenshotBase() {
    @Test fun foldManagerSearch() {
        // This phone holds 5205; the lab manager is mocked.
        c.reservations.put("lab", 5205, 15, System.currentTimeMillis() - 150_000)
        app()
        vm.openManager(vm.serverList.value.first { it.id == "lab" })
        idle(1500)
        // Typing a full port jumps to (selects) it; then widen the search to a range.
        compose.onNodeWithTag("portSearch").performTextInput("5205")
        idle(1200)
        compose.onNodeWithTag("portSearch").performTextReplacement("5201-5215")
        idle(600)
        capture("32-fold-manager-search-dark.png")
    }
}

class FoldManagerFilterShot : FoldScreenshotBase() {
    @Test fun foldManagerReservedFilter() {
        c.reservations.put("lab", 5205, 15, System.currentTimeMillis() - 150_000)
        app()
        vm.openManager(vm.serverList.value.first { it.id == "lab" })
        idle(1500)
        compose.onNodeWithTag("filter_RESERVED").performClick()
        compose.onNodeWithTag("portRow_5212").performClick()
        idle(1200)
        capture("32b-fold-manager-reserved-filter-dark.png")
    }
}

class FoldTabletopShot : FoldScreenshotBase() {
    @Test fun foldTabletop() {
        // Half open, landscape: the hinge runs across the middle of the 1840 px tall window.
        app(Adaptive.of(841f, Hinge(0, 920, 2208, 920, horizontal = true, separating = true, halfOpened = true)))
        startRunning(14)
        capture("33-fold-tabletop-dark.png")
    }
}

/** Phone-size shots of the 0.1.4 reservation controls and the collapsed changelog (real UI, mock manager). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h900dp-xhdpi")
abstract class PhoneShotBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }

    @Before fun tz() { TimeZone.setDefault(TimeZone.getTimeZone("America/Denver")) }

}

class ReservationTimerShot : PhoneShotBase() {
    @Test fun reservationTimerReleaseExtend() {
        val ui = local.net.iperf.manager.ManagerUi(
            serverTitle = "Lab iperf", baseUrl = "https://iperf.example.com", loaded = true,
            instances = listOf(
                ManagedInstance(5205, InstanceStatus.RESERVED, "reserved", "running", "ok", Reservation(by = "example-phone")),
                ManagedInstance(5206, InstanceStatus.RESERVED, "reserved", "running", "ok"),
                ManagedInstance(5212, InstanceStatus.RESERVED, "reserved", "running", "ok", Reservation(by = "alex", minutes = 30)),
                ManagedInstance(5213, InstanceStatus.STOPPED, "stopped", "stopped"),
            ),
            ports = PortAvailability(listOf(5213), emptyList()),
            mine = mapOf(5205 to 1_800_000_000_000L + 12 * 60_000 + 30_000, 5206 to 1_800_000_000_000L + 3 * 60_000 + 5_000),
        )
        compose.setContent {
            IperfTheme(dark = true) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text("Lab iperf") })
                        local.net.iperf.ui.manager.ManagerContent(ui, object : local.net.iperf.ui.manager.ManagerActions {}, nowMs = 1_800_000_000_000L)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "34-reservation-timer-dark.png").absolutePath)
    }

}

@Config(qualifiers = "w392dp-h1500dp-xhdpi")
class ChangelogShot : PhoneShotBase() {
    @Test fun collapsedChangelog() {
        val log = local.net.iperf.core.Changelog.parse(File(listOf("src/main/assets/CHANGELOG.md", "app/src/main/assets/CHANGELOG.md").first { File(it).exists() }).readText())
        compose.setContent {
            IperfTheme(dark = true) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text("About") })
                        local.net.iperf.ui.AboutContent("0.1.4", 5, log)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "35-changelog-collapsed-dark.png").absolutePath)
    }
}
