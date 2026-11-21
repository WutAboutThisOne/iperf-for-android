package local.net.iperf.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi
import local.net.iperf.manager.PortAvailability
import local.net.iperf.manager.Reservation
import local.net.iperf.ui.adaptive.Adaptive
import local.net.iperf.ui.adaptive.LocalAdaptive
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w841dp-h1400dp")
class PortSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val ui = ManagerUi(
        serverTitle = "Lab", baseUrl = "https://iperf.example.net", loaded = true,
        instances = (5201..5212).map { p ->
            when (p) {
                5204 -> ManagedInstance(p, InstanceStatus.RESERVED, "reserved", reservation = Reservation(by = "alex"))
                5205, 5206 -> ManagedInstance(p, InstanceStatus.RUNNING, "running")
                else -> ManagedInstance(p, InstanceStatus.STOPPED, "stopped")
            }
        },
        ports = PortAvailability((5201..5212).filter { it != 5204 }, emptyList()),
    )
    private val opened = mutableListOf<Int>()
    private val actions = object : ManagerActions { override fun openLogs(port: Int) { opened += port } }

    @Test fun compactSearchAndChips() {
        compose.setContent { ManagerContent(ui, actions) }
        compose.onNodeWithText("Port 5201").assertExists()
        compose.onNodeWithTag("portSearch").performTextInput("5205-5206")
        compose.onNodeWithText("Port 5205").assertIsDisplayed()
        compose.onNodeWithText("Port 5201").assertDoesNotExist()
        compose.onNodeWithTag("filter_RESERVED").performClick()
        compose.onNodeWithTag("noMatch").assertIsDisplayed()
        compose.onNodeWithTag("portSearch").performTextReplacement("")
        compose.onNodeWithText("Port 5204").assertIsDisplayed()
        compose.onNodeWithText("Port 5201").assertDoesNotExist()
        compose.onNodeWithTag("filter_RUNNING").performClick()
        compose.onNodeWithText("Port 5206").assertIsDisplayed()
        compose.onNodeWithText("Running 2").assertIsDisplayed()
        compose.onNodeWithText("Free 11").assertIsDisplayed()
    }

    @Test fun typingAPortJumpsToItWhenUnfolded() {
        compose.setContent { CompositionLocalProvider(LocalAdaptive provides Adaptive.of(841f)) { ManagerContent(ui, actions, wide = true) } }
        compose.onNodeWithText("Select a port to see its details and log").assertIsDisplayed()
        compose.onNodeWithTag("portSearch").performTextInput("5209")
        compose.waitForIdle()
        compose.onNodeWithTag("portDetail_5209").assertIsDisplayed()
        assertEquals(listOf(5209), opened)
    }
}
