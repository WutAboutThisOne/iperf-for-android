package local.net.iperf.core

/** One release in assets/CHANGELOG.md. */
data class ChangelogEntry(val version: String, val date: String?, val items: List<String>)

/**
 * Parses the running changelog. Format (newest first):
 * ```
 * ## 0.1.1 — 2026-11-17
 * - Change one
 * - Change two
 * ```
 * Lines that don't start with "## " or "- " are ignored, so the file can carry comments/prose.
 */
object Changelog {
    private val HEADER = Regex("^##\\s+v?([0-9][^\\s]*)\\s*(?:[—–-]+\\s*(.+))?$")

    fun parse(text: String): List<ChangelogEntry> {
        val out = mutableListOf<ChangelogEntry>()
        var version: String? = null; var date: String? = null; var items = mutableListOf<String>()
        fun flush() { version?.let { out += ChangelogEntry(it, date, items.toList()) } }
        text.lineSequence().map { it.trimEnd() }.forEach { line ->
            val h = HEADER.matchEntire(line.trim())
            when {
                h != null -> { flush(); version = h.groupValues[1]; date = h.groupValues[2].trim().ifEmpty { null }; items = mutableListOf() }
                line.trimStart().startsWith("- ") && version != null -> items += line.trimStart().removePrefix("- ").trim()
                // Indented continuation of the previous bullet.
                line.startsWith("  ") && line.isNotBlank() && items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        flush()
        return out
    }
}
