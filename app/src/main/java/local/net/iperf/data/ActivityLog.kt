package local.net.iperf.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/** Manager actions taken from this app. Start & test steps are tagged with [ActivityEntry.startAndTest]. */
enum class LogAction(val label: String) {
    START("Start"), STOP("Stop"), RESTART("Restart"), START_ALL("Start all"), STOP_ALL("Stop all"),
    RESERVE("Reserve"), RELEASE("Release"), EXTEND("Extend"), RELEASE_ALL("Release all mine"),
    WAIT_REACHABLE("Wait for port"), LOAD_PORT("Load port"), TEST_START("Test started"), TEST_END("Test ended");

    companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } }
}

data class ActivityEntry(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long,
    val serverId: String,
    val serverTitle: String,
    val ports: List<Int>,
    val action: LogAction,
    val ok: Boolean,
    /** Result text or the manager's error, already worded for people. Never a credential. */
    val detail: String? = null,
    val startAndTest: Boolean = false,
) {
    val actionLabel: String get() = (if (startAndTest) "Start & test · " else "") + action.label
    val result: String get() = if (ok) "OK" else "Failed"
}

/** Strips anything that could be a credential from a log line: the server's saved secrets and URL userinfo. */
object LogRedactor {
    private val userInfo = Regex("(https?://)[^/@\\s]+@", RegexOption.IGNORE_CASE)
    private val authHeader = Regex("(?i)(authorization|password|passwd|token)\\s*[:=]\\s*\\S+")

    fun redact(text: String?, secrets: Collection<String?>): String? {
        if (text == null) return null
        var t = userInfo.replace(text, "$1")
        t = authHeader.replace(t) { it.groupValues[1] + ": [redacted]" }
        secrets.filterNotNull().filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { t = t.replace(it, "[redacted]") }
        return t
    }
}

object ActivityEntries {
    /** Builds a log entry for [server], scrubbing every saved credential of that server from [detail]. */
    fun of(server: Server, sec: ServerSecrets, action: LogAction, ports: List<Int>, ok: Boolean, detail: String?,
           startAndTest: Boolean, now: Long = System.currentTimeMillis()): ActivityEntry {
        val secretValues = listOf(sec.webUser, sec.webPassword, sec.iperfUser, sec.iperfPassword, sec.apiUser, sec.apiPassword) +
            listOfNotNull(sec.managerLogin?.let { local.net.iperf.manager.ManagerClient.basic(it.first, it.second).removePrefix("Basic ") })
        return ActivityEntry(timeMs = now, serverId = server.id, serverTitle = server.title, ports = ports, action = action, ok = ok,
            detail = LogRedactor.redact(detail, secretValues), startAndTest = startAndTest)
    }
}

/** All servers' activity in one file, newest first, capped per server. */
class ActivityLogStore(file: java.io.File, private val maxPerServer: Int = 2000) : JsonListStore<ActivityEntry>(file) {
    override fun encode(item: ActivityEntry): JSONObject = JSONObject()
        .put("id", item.id).put("t", item.timeMs).put("server_id", item.serverId).put("server", item.serverTitle)
        .put("ports", JSONArray(item.ports)).put("action", item.action.name).put("ok", item.ok)
        .putOpt("detail", item.detail).put("start_and_test", item.startAndTest)

    override fun decode(o: JSONObject): ActivityEntry {
        val ports = o.optJSONArray("ports")
        return ActivityEntry(
            id = o.getString("id"), timeMs = o.getLong("t"), serverId = o.getString("server_id"), serverTitle = o.optString("server"),
            ports = (0 until (ports?.length() ?: 0)).map { ports!!.getInt(it) },
            action = LogAction.of(o.optString("action")) ?: throw IllegalArgumentException("action"),
            ok = o.optBoolean("ok"), detail = if (o.has("detail") && !o.isNull("detail")) o.getString("detail") else null,
            startAndTest = o.optBoolean("start_and_test"),
        )
    }

    override fun sort(items: List<ActivityEntry>): List<ActivityEntry> {
        val counts = HashMap<String, Int>()
        return items.sortedByDescending { it.timeMs }.filter { e ->
            val n = (counts[e.serverId] ?: 0) + 1
            counts[e.serverId] = n
            n <= maxPerServer
        }
    }

    fun add(entry: ActivityEntry) = update { it + entry }
    fun forServer(serverId: String): List<ActivityEntry> = items.value.filter { it.serverId == serverId }
    fun clear(serverId: String) = update { list -> list.filterNot { it.serverId == serverId } }

    companion object {
        val CSV_HEADER = listOf("time", "server", "ports", "action", "result", "detail")

        fun csv(entries: List<ActivityEntry>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
            appendLine(CSV_HEADER.joinToString(","))
            entries.forEach { e ->
                appendLine(listOf(Export.iso(e.timeMs, zone), e.serverTitle, e.ports.joinToString(" "), e.actionLabel, e.result, e.detail ?: "")
                    .joinToString(",") { Export.escape(it) })
            }
        }

        fun json(entries: List<ActivityEntry>, zone: ZoneId = ZoneId.systemDefault(), exportedAt: Long = System.currentTimeMillis()): String =
            JSONObject().put("app", "iPerf for Android").put("kind", "server_activity_log")
                .put("exported_at", Export.iso(exportedAt, zone))
                .put("entries", JSONArray().apply {
                    entries.forEach { e ->
                        put(JSONObject().put("time", Export.iso(e.timeMs, zone)).put("server", e.serverTitle)
                            .put("ports", JSONArray(e.ports)).put("action", e.actionLabel).put("ok", e.ok).putOpt("detail", e.detail))
                    }
                }).toString(2)
    }
}
