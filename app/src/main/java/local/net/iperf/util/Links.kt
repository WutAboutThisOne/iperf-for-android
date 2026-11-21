package local.net.iperf.util

import android.content.Intent
import android.net.Uri
import local.net.iperf.core.normaliseWebUrl

object Links {
    /** ACTION_VIEW intent for a server webpage, or null if the URL isn't usable. */
    fun webpage(raw: String): Intent? {
        val url = normaliseWebUrl(raw)?.takeIf { it.isNotEmpty() } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}
