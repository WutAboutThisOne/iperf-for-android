package local.net.iperf.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import local.net.iperf.AppContainer
import local.net.iperf.SampleData
import local.net.iperf.data.Server
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

/** The real app UI under a switchable size class / posture, as when the Fold is opened, closed or bent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w841dp-h701dp-xhdpi")
class AdaptiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val adaptive = mutableStateOf(Adaptive.of(841f))
    private lateinit var vm: AppViewModel

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        val c = AppContainer.get(app)
        c.foregroundServiceEnabled = false
        SampleData.history.forEach { c.history.add(it) }
        vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        vm.saveServer(Server(id = "", host = "192.168.1.20", label = "Office"))
        compose.setContent { IperfTheme { CompositionLocalProvider(LocalAdaptive provides adaptive.value) { IperfApp(vm) } } }
    }

    @Test fun unfoldedUsesRailAndSideBySideTest() {
        compose.onNodeWithTag("navRail").assertIsDisplayed()
        compose.onNodeWithTag("testSideBySide").assertExists()
        compose.onNodeWithText("Live graph").assertIsDisplayed()
        compose.onNodeWithTag("tab_TEST").assertDoesNotExist()
    }

    @Test fun foldingKeepsInputAndSelection() {
        compose.onNodeWithText("Duration").performTextReplacement("37")
        adaptive.value = Adaptive.Compact
        compose.waitForIdle()
        compose.onNodeWithTag("tab_TEST").assertIsDisplayed()
        compose.onNodeWithTag("navRail").assertDoesNotExist()
        compose.onNodeWithText("37").assertExists()
        adaptive.value = Adaptive.of(841f)
        compose.waitForIdle()
        compose.onNodeWithText("37").assertExists()
    }

    @Test fun historyIsListDetailAndSurvivesFolding() {
        compose.onNodeWithTag("rail_HISTORY").performClick()
        compose.onNodeWithText("Select a test to see its details").assertIsDisplayed()
        val r = SampleData.history.first()
        compose.onNodeWithTag("historyRow_${r.id}").performClick()
        compose.onNodeWithTag("historyDetailPane").assertExists()
        compose.onNodeWithTag("historyListDetail").assertExists()
        // Another tab while unfolded: the selection doesn't take over the screen, and is still there on return.
        compose.onNodeWithTag("rail_TEST").performClick()
        compose.onNodeWithTag("testSideBySide").assertExists()
        compose.onNodeWithText("Result").assertDoesNotExist()
        compose.onNodeWithTag("rail_HISTORY").performClick()
        compose.onNodeWithTag("historyDetailPane").assertExists()
        // Folding shows the same result full screen, with Back.
        adaptive.value = Adaptive.Compact
        compose.waitForIdle()
        compose.onNodeWithText("Result").assertIsDisplayed()
        compose.onNodeWithTag("historyListDetail").assertDoesNotExist()
    }

    @Test fun tabletopPutsGraphAboveControls() {
        adaptive.value = Adaptive.of(841f, Hinge(0, 700, 1682, 700, horizontal = true, separating = true, halfOpened = true))
        compose.waitForIdle()
        compose.onNodeWithTag("testTabletop").assertExists()
        compose.onNodeWithTag("paneTop").assertExists()
        compose.onNodeWithTag("paneBottom").assertExists()
        val top = compose.onNodeWithText("Live graph").fetchSemanticsNode().boundsInWindow
        val start = compose.onNodeWithTag("start").fetchSemanticsNode().boundsInWindow
        val hingeTop = compose.onNodeWithTag("paneTop").fetchSemanticsNode().boundsInWindow.bottom
        val bottomPane = compose.onNodeWithTag("paneBottom").fetchSemanticsNode().boundsInWindow.top
        assert(top.bottom <= hingeTop) { "graph card must be above the hinge" }
        assert(start.top >= bottomPane) { "controls must be below the hinge" }
        assert(bottomPane - hingeTop >= 20f) { "a margin is kept clear around the crease" }
    }
}
