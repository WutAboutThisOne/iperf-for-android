package local.net.iperf.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** Copies a login value, hides it from clipboard previews, and clears it again after [CLEAR_AFTER_MS]. */
object SecureClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    const val LABEL = "iPerf for Android login"
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copy(context: Context, value: String, sensitive: Boolean) {
        val cm = context.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, value)
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        pending?.let(handler::removeCallbacks)
        val r = Runnable { clearIfOurs(cm) }
        pending = r
        handler.postDelayed(r, CLEAR_AFTER_MS)
    }

    /** Clears only if the clip is still ours (or unreadable because we're in the background). */
    internal fun clearIfOurs(cm: ClipboardManager) {
        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        if (desc == null || desc.label == LABEL) runCatching { cm.clearPrimaryClip() }
        pending = null
    }
}
