package local.net.iperf.core

import java.net.URI

/**
 * Normalises a "server webpage" field: trims it, adds https:// when there's no scheme, and accepts only
 * http/https URLs with a host. Returns "" for a blank field and null when it isn't a usable URL.
 */
fun normaliseWebUrl(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.any { it.isWhitespace() }) return null
    val schemeMatch = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(t)
    val withScheme = when {
        schemeMatch != null -> t
        // "host:8080/path" has a colon but no scheme; anything else with "x:" (mailto:, javascript:) is rejected.
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d)").containsMatchIn(t) -> return null
        else -> "https://$t"
    }
    return try {
        val u = URI(withScheme)
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") null
        else if (u.host.isNullOrBlank()) null
        else withScheme.replaceFirst(Regex("^[A-Za-z]+://"), "$scheme://")
    } catch (_: Exception) {
        null
    }
}
