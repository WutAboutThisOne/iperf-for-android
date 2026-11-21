package local.net.iperf.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import local.net.iperf.AppContainer
import local.net.iperf.MainActivity
import local.net.iperf.data.Server
import local.net.iperf.run.RunState
import local.net.iperf.service.TestRunServiceTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNotificationPermissionTest {
    val compose = createAndroidComposeRule<MainActivity>()
    private val engine = TestRunServiceTest.ChannelEngine()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.getSharedPreferences("settings", 0).edit().clear().commit()
            val c = AppContainer.get(app)
            c.engine.delegate = engine
            c.foregroundServiceEnabled = false
            val s = c.servers.upsert(Server(id = "", host = "192.0.2.10"))
            c.prefs.selectedServerId = s.id
        }
    }).around(compose)

    @Test fun firstStartAsksForNotificationsOnceAndRunsEvenIfDenied() {
        val shadow = shadowOf(compose.activity)
        compose.onNodeWithTag("start").performScrollTo().performClick()
        compose.waitForIdle()
        val req = shadow.lastRequestedPermission
        org.junit.Assert.assertNotNull(req)
        assertTrue(req.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        val runner = AppContainer.get(compose.activity.application).runner
        assertTrue("waits for the answer", runner.state.value !is RunState.Running)
        // Denied: the test still runs.
        compose.activity.onRequestPermissionsResult(req.requestCode, req.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        compose.waitForIdle()
        assertTrue(runner.state.value is RunState.Running)
        runner.stop()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        compose.waitForIdle()
        assertEquals(true, compose.activity.getSharedPreferences("settings", 0).getBoolean("notifications_asked", false))
        // Asked once only: the next start goes straight to the test, with no new prompt.
        compose.onNodeWithTag("start").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(shadow.lastRequestedPermission === req)
    }
}
