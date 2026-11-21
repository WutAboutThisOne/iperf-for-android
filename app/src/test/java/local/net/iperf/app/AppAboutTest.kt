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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import local.net.iperf.BuildConfig
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
class AppAboutTest {
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

    @Test fun aboutShowsVersionAndChangelog() {
        compose.onNodeWithContentDescription("About").performClick()
        compose.onNodeWithTag("aboutVersion").assertTextEquals("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        compose.onNodeWithText("What's new").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.1.0").performScrollTo().assertIsDisplayed()
        // Latest open, older collapsed until tapped.
        compose.onNodeWithText("Renamed from Iperf Local", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Endless tests", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("changelog_0.1.2").performScrollTo().performClick()
        compose.onNodeWithText("Endless tests", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("changelog_0.1.2").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Endless tests", substring = true).assertCountEquals(0)
        // Licence texts live on their own page now, linked from About.
        compose.onAllNodesWithText("OpenSSL 3.5.9").assertCountEquals(0)
        compose.onNodeWithTag("openLicences").performScrollTo().performClick()
        compose.onNodeWithTag("licencesPage").assertIsDisplayed()
        compose.onNodeWithText("OpenSSL 3.5.9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open-source licences").assertIsDisplayed()
    }

    @Test fun licencesListOpensEachComponentOnItsOwnPage() {
        compose.onNodeWithContentDescription("About").performClick()
        compose.onNodeWithTag("openLicences").performScrollTo().performClick()
        // Compact list: names and licence types only, no full texts.
        compose.onNodeWithText("iPerf for Android ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("iperf3 3.22").assertIsDisplayed()
        compose.onNodeWithText("BSD-3-Clause (cJSON: MIT)").assertIsDisplayed()
        compose.onAllNodesWithTag("licenceText").assertCountEquals(0)
        compose.onAllNodesWithText("Regents of the University of California", substring = true).assertCountEquals(0)
        // Tap iperf3: its own page with its own text.
        compose.onNodeWithTag("licence_1").performClick()
        compose.onNodeWithTag("licenceDetail").assertIsDisplayed()
        compose.onNodeWithTag("licenceText").assertTextContains("Regents of the University of California", substring = true)
        compose.onNodeWithText("iperf3 3.22").assertIsDisplayed() // top bar title
        // Back returns to the list, then a different component shows its own (Apache) text.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("licencesPage").assertIsDisplayed()
        compose.onAllNodesWithTag("licenceDetail").assertCountEquals(0)
        compose.onNodeWithTag("licence_0").performClick()
        compose.onNodeWithTag("licenceText").assertTextContains("Apache License", substring = true)
        compose.onAllNodesWithText("Regents of the University of California", substring = true).assertCountEquals(0)
    }

    @Test fun invalidHostIsRejected() {
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithTag("hostField").performTextInput("-s")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a host name or IP address").assertIsDisplayed()
    }
}
