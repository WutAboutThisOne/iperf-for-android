package local.net.iperf.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import local.net.iperf.MainActivity
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
/**
 * Each test that saves a server lives in its own class: Robolectric keeps the previous test's Compose root
 * registered within one JVM after a dialog closes, which confuses node lookups (forkEvery = 1 is per class).
 */
class AppPortPromptTest {
    val compose = createAndroidComposeRule<MainActivity>()

    /** Robolectric can reuse the app's data dir between tests in one JVM: start every test from a clean install. */
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.content.Context>()
            app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.getSharedPreferences("settings", 0).edit().clear().commit()
        }
    }).around(compose)

    @Test fun portPromptUsesOneTimePortWithoutChangingSavedPort() {
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithTag("hostField").performTextInput("192.168.1.20")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        // Picking the server from the list asks which port to use.
        compose.onNodeWithTag("serverPicker").performClick()
        compose.onAllNodesWithText("192.168.1.20:5201")[0].performClick()
        compose.onNodeWithText("Use default (5201)").assertIsDisplayed()
        compose.onNodeWithTag("otherPortField").performTextInput("70000")
        compose.onNodeWithText("Enter 1–65535").assertIsDisplayed()
        compose.onNodeWithTag("useOtherPort").assertIsNotEnabled()
        compose.onNodeWithTag("otherPortField").performTextClearance()
        compose.onNodeWithTag("otherPortField").performTextInput("5202")
        compose.onNodeWithTag("useOtherPort").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("192.168.1.20  ·  192.168.1.20:5202").assertIsDisplayed()
        compose.onNodeWithText("Port 5202 for this run").assertIsDisplayed()
        // The saved server still says 5201.
        compose.onNodeWithTag("tab_SERVERS").performClick()
        compose.onNodeWithText("192.168.1.20:5201").assertIsDisplayed()
        // Back on Test: "Use saved" drops the one-time port; "Change port" → default keeps 5201.
        compose.onNodeWithTag("tab_TEST").performClick()
        compose.onNodeWithText("Use saved").performClick()
        compose.onNodeWithText("192.168.1.20  ·  192.168.1.20:5201").assertIsDisplayed()
        compose.onNodeWithTag("changePort").performClick()
        compose.onNodeWithTag("useDefaultPort").performClick()
        compose.onNodeWithText("Port 5201 (saved)").assertIsDisplayed()
    }
}
