package local.net.iperf.manager

import org.json.JSONArray
import org.json.JSONObject

enum class InstanceStatus(val label: String) {
    RUNNING("Running"), STOPPED("Stopped"), RESERVED("Reserved"), DEGRADED("Degraded"),
    RESTARTING("Restarting"), BLOCKED("Blocked"), UNKNOWN("Unknown");

    companion object {
        fun of(raw: String?): InstanceStatus = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class Reservation(val until: String? = null, val minutes: Int? = null, val by: String? = null, val note: String? = null) {
    fun describe(): String = listOfNotNull(by?.let { "by $it" }, until?.let { "until ${localTime(it)}" }, minutes?.let { "$it min" }, note)
        .joinToString(" · ").ifEmpty { "reserved" }
}

data class ManagedInstance(
    val port: Int,
    val status: InstanceStatus,
    val rawStatus: String,
    val desiredState: String? = null,
    val health: String? = null,
    val reservation: Reservation? = null,
)

data class PortAvailability(val available: List<Int>, val blocked: List<Int>)

data class LogWindow(val lines: List<String>, val next: Int?, val total: Int?)

/** Result of start-all / stop-all. */
data class BulkResult(val ok: List<Int>, val errors: Map<Int, String>) {
    fun describe(verb: String): String = buildString {
        append("${ok.size} $verb")
        if (errors.isNotEmpty()) append(", ${errors.size} failed (" + errors.entries.take(3).joinToString { "${it.key}: ${it.value}" } +
            (if (errors.size > 3) ", …" else "") + ")")
    }
}

/** Lenient decoding: the manager's JSON is matched by field name, unknown fields are ignored. */
object ManagerJson {
    private fun JSONObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) opt(k)?.let { v -> if (v is JSONObject || v is JSONArray) null else v.toString() } else null }
            ?.takeIf { it.isNotBlank() }

    private fun Any?.asArray(vararg keys: String): JSONArray? = when (this) {
        is JSONArray -> this
        is JSONObject -> keys.firstNotNullOfOrNull { optJSONArray(it) }
        else -> null
    }

    private fun ints(a: JSONArray?): List<Int> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        when (val v = a!!.opt(i)) {
            is Number -> v.toInt()
            is JSONObject -> v.optInt("port", -1).takeIf { it > 0 }
            else -> v?.toString()?.toIntOrNull()
        }
    }

    private fun health(o: JSONObject): String? = when (val h = o.opt("health")) {
        null, JSONObject.NULL -> null
        is JSONObject -> h.str("status", "state", "message", "detail") ?: h.optBoolean("ok", true).let { if (it) "ok" else "failing" }
        is Boolean -> if (h) "healthy" else "unhealthy"
        else -> h.toString()
    }

    private fun reservation(o: JSONObject): Reservation? {
        val r = o.optJSONObject("reservation") ?: o.optJSONObject("reserved") ?: return null
        return Reservation(
            until = r.str("expires_at", "until", "expires", "reserved_until"),
            minutes = r.optInt("minutes", -1).takeIf { it > 0 },
            by = r.str("by", "owner", "client", "reserved_by", "user"),
            note = r.str("note", "label", "purpose"),
        )
    }

    fun instances(body: String): List<ManagedInstance> {
        val root: Any = body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
        val arr = root.asArray("instances", "data", "items")
        if (arr != null) return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { instance(it, null) } }.sortedBy { it.port }
        // Map form: {"5201": {...}, ...} or {"instances": {"5201": {...}}}
        val map = (root as JSONObject).optJSONObject("instances") ?: root
        return map.keys().asSequence().mapNotNull { k -> map.optJSONObject(k)?.let { instance(it, k.toIntOrNull()) } }.toList().sortedBy { it.port }
    }

    private fun instance(o: JSONObject, keyPort: Int?): ManagedInstance? {
        val port = o.optInt("port", keyPort ?: -1).takeIf { it in 1..65535 } ?: return null
        val raw = o.str("status", "state") ?: "unknown"
        return ManagedInstance(port, InstanceStatus.of(raw), raw, o.str("desired_state", "desired"), health(o), reservation(o))
    }

    fun ports(body: String): PortAvailability {
        val o = JSONObject(body)
        return PortAvailability(ints(o.optJSONArray("available_ports")).sorted(), ints(o.optJSONArray("blocked_ports")).sorted())
    }

    fun logs(body: String): LogWindow {
        val t = body.trim()
        val root: Any = if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        val arr = root.asArray("lines", "logs", "entries", "log")
        val lines = (0 until (arr?.length() ?: 0)).map { i ->
            when (val v = arr!!.opt(i)) {
                is JSONObject -> listOfNotNull(v.str("ts", "time", "timestamp"), v.str("line", "message", "msg", "text")).joinToString("  ")
                else -> v.toString()
            }
        }.let { l ->
            // Some servers return the log as one string.
            if (arr == null && root is JSONObject) root.str("log", "logs", "text")?.lines() ?: l else l
        }
        val o = root as? JSONObject
        return LogWindow(lines, o?.optInt("next", -1)?.takeIf { it >= 0 }, o?.optInt("total", -1)?.takeIf { it >= 0 })
    }

    fun bulk(body: String, okKey: String): BulkResult {
        val o = JSONObject(body)
        val errs = mutableMapOf<Int, String>()
        when (val e = o.opt("errors")) {
            is JSONObject -> e.keys().forEach { k -> k.toIntOrNull()?.let { errs[it] = e.optString(k) } }
            is JSONArray -> for (i in 0 until e.length()) e.optJSONObject(i)?.let { x ->
                x.optInt("port", -1).takeIf { it > 0 }?.let { errs[it] = x.str("error", "message", "detail") ?: "error" }
            }
        }
        return BulkResult(ints(o.optJSONArray(okKey)).sorted(), errs)
    }

    /** The manager's {"error": "..."} body, if any. */
    fun error(body: String?): String? = runCatching { JSONObject(body ?: "").str("error", "message", "detail") }.getOrNull()
}

/** ISO-8601 with an offset, or epoch seconds / millis; null if unparseable. */
fun parseInstant(raw: String?): java.time.Instant? {
    val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { java.time.OffsetDateTime.parse(t).toInstant() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(t) }.getOrNull()
        ?: t.toLongOrNull()?.let { n -> if (n > 100_000_000_000L) java.time.Instant.ofEpochMilli(n) else java.time.Instant.ofEpochSecond(n) }
}

/** "2026-11-17T22:00:00Z" → "15:00" in the phone's zone; anything unparseable is shown as-is. */
internal fun localTime(raw: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    parseInstant(raw)?.let { java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(it.atZone(zone)) } ?: raw

/**
 * When [inst]'s reservation ends: the manager's own expiry if it sends one, otherwise the expiry this phone
 * recorded when it reserved ([mineUntilMs]). Null if neither is known.
 */
fun reservationEndsAt(inst: ManagedInstance?, mineUntilMs: Long?): Long? =
    parseInstant(inst?.reservation?.until)?.toEpochMilli() ?: mineUntilMs
