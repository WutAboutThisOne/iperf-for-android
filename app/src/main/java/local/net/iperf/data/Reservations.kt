package local.net.iperf.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** A port reservation made from this phone, so "Release all mine", the timer and the notification know about it. */
data class MyReservation(val serverId: String, val port: Int, val reservedAtMs: Long, val expiresAtMs: Long)

interface ReservationTracker {
    val items: StateFlow<List<MyReservation>>
    fun put(serverId: String, port: Int, minutes: Int, now: Long = System.currentTimeMillis())
    fun remove(serverId: String, port: Int)

    /** Reservations on [serverId] that haven't expired (with a minute's grace for clock skew), oldest first. */
    fun forServer(serverId: String, now: Long = System.currentTimeMillis()): List<MyReservation> =
        items.value.filter { it.serverId == serverId && it.expiresAtMs + GRACE_MS > now }.sortedBy { it.port }

    fun find(serverId: String, port: Int, now: Long = System.currentTimeMillis()): MyReservation? =
        forServer(serverId, now).firstOrNull { it.port == port }

    companion object { const val GRACE_MS = 60_000L }
}

/** Kept in a file so a reservation made before the app was closed can still be released (until it expires). */
class ReservationStore(file: java.io.File) : JsonListStore<MyReservation>(file), ReservationTracker {
    override fun encode(item: MyReservation): JSONObject = JSONObject()
        .put("server", item.serverId).put("port", item.port).put("at", item.reservedAtMs).put("until", item.expiresAtMs)

    override fun decode(o: JSONObject) = MyReservation(o.getString("server"), o.getInt("port"), o.optLong("at"), o.getLong("until"))

    // Drop entries that expired long ago; the manager has forgotten them too.
    override fun sort(items: List<MyReservation>): List<MyReservation> {
        val cutoff = System.currentTimeMillis() - 3600_000L
        return items.filter { it.expiresAtMs > cutoff }
    }

    override fun put(serverId: String, port: Int, minutes: Int, now: Long) = update { list ->
        list.filterNot { it.serverId == serverId && it.port == port } + MyReservation(serverId, port, now, now + minutes * 60_000L)
    }

    override fun remove(serverId: String, port: Int) = update { list -> list.filterNot { it.serverId == serverId && it.port == port } }
}

/** For tests and previews. */
class InMemoryReservations : ReservationTracker {
    private val _items = MutableStateFlow<List<MyReservation>>(emptyList())
    override val items: StateFlow<List<MyReservation>> = _items.asStateFlow()
    override fun put(serverId: String, port: Int, minutes: Int, now: Long) = _items.update { list ->
        list.filterNot { it.serverId == serverId && it.port == port } + MyReservation(serverId, port, now, now + minutes * 60_000L)
    }
    override fun remove(serverId: String, port: Int) = _items.update { list -> list.filterNot { it.serverId == serverId && it.port == port } }
}

/** "14:59 left", "1 h 05 min left", "expired". */
fun formatTimeLeft(ms: Long): String {
    if (ms <= 0) return "expired"
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) "$h h %02d min left".format(m) else "%d:%02d left".format(m, s)
}
