package local.net.iperf.ui

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import local.net.iperf.manager.InstanceAction
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi
import local.net.iperf.manager.PortAvailability
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import local.net.iperf.ui.servers.PortPromptDialog
import local.net.iperf.ui.servers.WebpageDialog
import local.net.iperf.ui.theme.IperfTheme
import local.net.iperf.util.SecureClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerAndDialogsUiTest {
    @get:Rule val compose = createComposeRule()
    private val lab = Server("n1", "iperf.example.com", 5201, "Lab", "https://iperf.example.com", managerEnabled = true)

    @Test fun portPromptListsFreePortsAndReserves() {
        var chosen: Pair<Int, Boolean>? = null
        compose.setContent {
            IperfTheme(dark = true) {
                PortPromptDialog(PortPromptUi(lab, managed = true, ports = PortAvailability(listOf(5203, 5207), listOf(5300))),
                    onChoose = { p, r -> chosen = p to r }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Free ports on the manager (2)").assertIsDisplayed()
        compose.onNodeWithTag("freePort_5207").performClick()
        compose.onNodeWithTag("reserveCheck").performScrollTo().performClick()
        compose.onNodeWithTag("useOtherPort").performScrollTo().performClick()
        assertEquals(5207 to true, chosen)
    }

    @Test fun portPromptDefaultWithoutManager() {
        var chosen: Pair<Int, Boolean>? = null
        compose.setContent {
            IperfTheme { PortPromptDialog(PortPromptUi(lab.copy(managerEnabled = false, port = 5209)), { p, r -> chosen = p to r }, {}) }
        }
        compose.onNodeWithText("Free ports on the manager", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Use default (5209)").performClick()
        assertEquals(5209 to false, chosen)
    }

    // 0.1.4: the port search sits above the list, so give both cards room on screen.
    @Config(qualifiers = "w392dp-h900dp")
    @Test fun managerScreenActionsAndConfirmations() {
        val calls = mutableListOf<String>()
        val ui = ManagerUi("Lab", "https://iperf.example.com", instances = listOf(
            ManagedInstance(5201, InstanceStatus.RUNNING, "running", "running", "ok"),
            ManagedInstance(5202, InstanceStatus.STOPPED, "stopped", "stopped"),
        ), ports = PortAvailability(listOf(5202), emptyList()), loaded = true)
        compose.setContent {
            IperfTheme {
                ManagerContent(ui, object : ManagerActions {
                    override fun act(port: Int, action: InstanceAction) { calls += "$action $port" }
                    override fun stopAll() { calls += "stopAll" }
                    override fun openLogs(port: Int) { calls += "logs $port" }
                })
            }
        }
        compose.onNodeWithText("Port 5201").assertIsDisplayed()
        compose.onNodeWithText("Start").performScrollTo().performClick() // only the stopped instance offers Start
        compose.onNodeWithTag("logs_5201").performScrollTo().performClick()
        compose.onNodeWithTag("stopAll").performScrollTo().performClick()
        compose.onNodeWithText("Stop all instances?").assertIsDisplayed()
        assertFalse("stopAll" in calls) // not before confirming
        compose.onNodeWithTag("confirmBulk").performClick()
        assertEquals(listOf("START 5202", "logs 5201", "stopAll"), calls)
    }

    @Test fun webpageDialogHidesPasswordAndCopies() {
        val copied = mutableListOf<Triple<String, Boolean, String>>()
        compose.setContent {
            IperfTheme {
                WebpageDialog(lab, ServerSecrets(webUser = "example-user", webPassword = "Example-Pass-123"), {}, { v, s, w -> copied += Triple(v, s, w) }, {})
            }
        }
        compose.onNodeWithText("Example-Pass-123").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithText("Example-Pass-123").assertIsDisplayed()
        compose.onNodeWithTag("copyPassword").performClick()
        compose.onNodeWithTag("copyUsername").performClick()
        assertEquals(listOf(Triple("Example-Pass-123", true, "Password"), Triple("example-user", false, "Username")), copied)
    }

    @Test fun clipboardIsClearedOnlyIfStillOurs() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        SecureClipboard.copy(ctx, "Example-Pass-123", sensitive = true)
        assertEquals("Example-Pass-123", cm.primaryClip!!.getItemAt(0).text)
        assertTrue(cm.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        SecureClipboard.clearIfOurs(cm)
        assertTrue(cm.primaryClip == null || cm.primaryClip!!.itemCount == 0 || cm.primaryClip!!.getItemAt(0).text.isNullOrEmpty())
        // Someone else's clip is left alone.
        cm.setPrimaryClip(android.content.ClipData.newPlainText("other", "keep me"))
        SecureClipboard.clearIfOurs(cm)
        assertEquals("keep me", cm.primaryClip!!.getItemAt(0).text)
        assertNull(null)
    }
}
