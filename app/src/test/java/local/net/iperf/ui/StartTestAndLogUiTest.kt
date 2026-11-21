package local.net.iperf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import local.net.iperf.manager.InstanceStatus
import local.net.iperf.manager.ManagedInstance
import local.net.iperf.manager.ManagerUi
import local.net.iperf.manager.StartTestProgress
import local.net.iperf.ui.manager.ActivityLogMenu
import local.net.iperf.ui.manager.ManagerActions
import local.net.iperf.ui.manager.ManagerContent
import local.net.iperf.ui.theme.IperfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h1200dp-xhdpi")
class StartTestAndLogUiTest {
    @get:Rule val compose = createComposeRule()
    private val ui = ManagerUi("Lab", "https://iperf.example.net", loaded = true, instances = listOf(
        ManagedInstance(5201, InstanceStatus.RUNNING, "running"),
        ManagedInstance(5203, InstanceStatus.STOPPED, "stopped"),
        ManagedInstance(5206, InstanceStatus.BLOCKED, "blocked"),
    ))

    @Test fun startAndTestButtonsToggleAndActivityLink() {
        val started = mutableListOf<Int>(); var auto: Boolean? = null; var openedLog = false
        compose.setContent {
            IperfTheme(dark = true) {
                ManagerContent(ui, object : ManagerActions {
                    override fun startAndTest(instance: ManagedInstance) { started += instance.port }
                    override fun setAutoStart(on: Boolean) { auto = on }
                    override fun openActivity() { openedLog = true }
                }, autoStart = true)
            }
        }
        compose.onNodeWithText("Start & test").assertIsDisplayed()
        compose.onNodeWithText("Reserve & test").assertIsDisplayed()
        compose.onAllNodesWithTag("startTest_5206").assertCountEquals(0)
        compose.onNodeWithTag("startTest_5203").performScrollTo().performClick()
        compose.onNodeWithTag("startTest_5201").performScrollTo().performClick()
        assertEquals(listOf(5203, 5201), started)
        compose.onNodeWithTag("autoStartSwitch").performClick()
        assertEquals(false, auto)
        compose.onNodeWithTag("openActivity").performClick()
        assertTrue(openedLog)
    }

    @Test fun progressDisablesButtonsAndErrorsAreShownPlainly() {
        var progress by mutableStateOf<StartTestProgress?>(StartTestProgress(5203, "Waiting for port 5203 to accept connections…"))
        compose.setContent { IperfTheme { ManagerContent(ui, object : ManagerActions {}, startTest = progress) } }
        compose.onNodeWithText("Waiting for port 5203 to accept connections…").assertIsDisplayed()
        compose.onNodeWithTag("startTest_5201").assertIsNotEnabled()
        progress = StartTestProgress(5203, "", error = "Couldn't start port 5203: The manager hit an error (HTTP 500). The reservation was released.")
        compose.onNodeWithText("Couldn't start port 5203: The manager hit an error (HTTP 500). The reservation was released.").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test fun clearLogAsksFirst() {
        var cleared = 0; val exported = mutableListOf<Boolean>()
        compose.setContent {
            var confirm by remember { mutableStateOf(false) }
            IperfTheme {
                Column {
                    ActivityLogMenu(true, onExport = { exported += it }, onClear = { confirm = true })
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear activity log?") },
                        confirmButton = { TextButton({ cleared++; confirm = false }, Modifier.testTag("confirmClearActivity")) { Text("Clear") } })
                }
            }
        }
        compose.onNodeWithTag("activityMenu").performClick()
        compose.onNodeWithText("Export CSV").performClick()
        compose.onNodeWithTag("activityMenu").performClick()
        compose.onNodeWithTag("clearActivity").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithText("Clear activity log?").assertIsDisplayed()
        compose.onNodeWithTag("confirmClearActivity").performClick()
        assertEquals(1, cleared)
        assertEquals(listOf(true), exported)
    }
}
