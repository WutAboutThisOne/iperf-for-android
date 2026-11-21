package local.net.iperf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import local.net.iperf.manager.InstanceAction
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi
import local.net.iperf.manager.Reservation
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h1200dp")
class ReservationUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_800_000_000_000L
    private val calls = mutableListOf<String>()
    private val actions = object : ManagerActions {
        override fun act(port: Int, action: InstanceAction) { calls += "${action.name} $port" }
        override fun extend(port: Int) { calls += "extend $port" }
        override fun releaseAllMine() { calls += "releaseAllMine" }
    }
    private fun ui(extend: Boolean = true) = ManagerUi(
        serverTitle = "Lab", baseUrl = "https://iperf.example.net", loaded = true,
        instances = listOf(
            ManagedInstance(5205, InstanceStatus.RESERVED, "reserved", reservation = Reservation(by = "phone")),
            ManagedInstance(5206, InstanceStatus.RESERVED, "reserved", reservation = Reservation(by = "alex", minutes = 30)),
        ),
        mine = mapOf(5205 to now + 12 * 60_000 + 34_000),
        extendSupported = extend,
    )

    @Test fun ourReservationShowsTimerReleaseAndExtend() {
        compose.setContent { ManagerContent(ui(), actions, nowMs = now) }
        compose.onNodeWithText("Reserved by you · 12:34 left").assertIsDisplayed()
        compose.onNodeWithText("Reserved · by alex · 30 min").assertIsDisplayed()
        compose.onNodeWithTag("extend_5205").performClick()
        compose.onNodeWithTag("extend_5206").assertDoesNotExist() // not ours
        compose.onNodeWithTag("release_5206").assertExists()
        compose.onNodeWithTag("release_5205").performClick()
        assertEquals(listOf("extend 5205", "RELEASE 5205"), calls)
    }

    @Test fun extendHiddenWhenManagerRefuses() {
        compose.setContent { ManagerContent(ui(extend = false), actions, nowMs = now) }
        compose.onNodeWithTag("extend_5205").assertDoesNotExist()
        compose.onNodeWithTag("release_5205").assertExists()
    }

    @Test fun releaseAllMineConfirmsFirst() {
        compose.setContent { ManagerContent(ui(), actions, nowMs = now) }
        compose.onNodeWithText("Release all mine (1)").performClick()
        compose.onNodeWithText("Release all your reservations?").assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("confirmReleaseMine").performClick()
        assertEquals(listOf("releaseAllMine"), calls)
    }
}
