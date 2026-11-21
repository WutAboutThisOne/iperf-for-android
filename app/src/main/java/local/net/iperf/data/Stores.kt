package local.net.iperf.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Small JSON-array-in-a-file store with atomic writes. All mutation is synchronised. */
abstract class JsonListStore<T>(private val file: File) {
    protected abstract fun encode(item: T): JSONObject
    protected abstract fun decode(o: JSONObject): T
    protected open fun sort(items: List<T>): List<T> = items

    // Lazy: subclass properties (e.g. maxEntries used by sort) aren't initialised during the base constructor.
    private val _items by lazy { MutableStateFlow(load()) }
    val items: StateFlow<List<T>> by lazy { _items.asStateFlow() }

    private fun load(): List<T> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            sort((0 until arr.length()).mapNotNull { i -> runCatching { decode(arr.getJSONObject(i)) }.getOrNull() })
        }
    } catch (_: Exception) {
        // Corrupt file: keep a copy for inspection and start fresh rather than crash.
        runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
        emptyList()
    }

    @Synchronized
    protected fun update(transform: (List<T>) -> List<T>) {
        val next = sort(transform(_items.value))
        write(next)
        _items.value = next
    }

    private fun write(items: List<T>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().apply { items.forEach { put(encode(it)) } }.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

class ServerStore(file: File) : JsonListStore<Server>(file) {
    override fun encode(item: Server) = Codec.server(item)
    override fun decode(o: JSONObject) = Codec.server(o)

    fun upsert(server: Server): Server {
        val s = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        update { list -> if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s }
        return s
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }
    fun find(id: String?): Server? = items.value.firstOrNull { it.id == id }
}

class HistoryStore(file: File, private val maxEntries: Int = 500) : JsonListStore<TestRecord>(file) {
    override fun encode(item: TestRecord) = Codec.record(item)
    override fun decode(o: JSONObject) = Codec.record(o)
    override fun sort(items: List<TestRecord>) = items.sortedByDescending { it.timestamp }.take(maxEntries)

    fun add(record: TestRecord) = update { it.filterNot { r -> r.id == record.id } + record }
    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }
    fun deleteAll() = update { emptyList() }
}
