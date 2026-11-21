package local.net.iperf.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import local.net.iperf.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MainActivity picks the layout from the real window size: Fold inner screen ≈ 841×701 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w841dp-h701dp-xhdpi")
class AppFoldDetectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unfoldedWindowGetsTheRail() {
        compose.onNodeWithTag("navRail").assertIsDisplayed()
        compose.onNodeWithTag("testSideBySide").assertExists()
    }
}
