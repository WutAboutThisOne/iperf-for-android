package local.net.iperf.manager

/** What was typed in the Manager screen's port search. */
sealed interface PortQuery {
    data object All : PortQuery
    /** A port number, or the start of one ("52" matches 5201…5299 until a full port is typed). */
    data class Single(val text: String, val port: Int) : PortQuery
    data class Range(val from: Int, val to: Int) : PortQuery
    data class Invalid(val message: String) : PortQuery

    companion object {
        private val range = Regex("""^(\d{1,5})\s*(?:-|–|—|\.\.|to)\s*(\d{1,5})$""", RegexOption.IGNORE_CASE)
        private val single = Regex("""^\d{1,5}$""")

        fun parse(raw: String): PortQuery {
            val t = raw.trim()
            if (t.isEmpty()) return All
            if (single.matches(t)) return t.toInt().let { if (it in 1..65535) Single(t, it) else Invalid("Ports go from 1 to 65535") }
            range.matchEntire(t)?.let { m ->
                var a = m.groupValues[1].toInt(); var b = m.groupValues[2].toInt()
                if (a !in 1..65535 || b !in 1..65535) return Invalid("Ports go from 1 to 65535")
                if (a > b) { val x = a; a = b; b = x }
                return Range(a, b)
            }
            // "5250-" while typing a range: treat as the start.
            Regex("""^(\d{1,5})\s*(?:-|–|—|\.\.|to)\s*$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
                val a = m.groupValues[1].toInt()
                if (a in 1..65535) return Range(a, 65535)
            }
            return Invalid("Type a port (5250) or a range (5250-5260)")
        }
    }
}

enum class PortFilter(val label: String) { ALL("All"), FREE("Free"), RESERVED("Reserved"), RUNNING("Running") }

fun isReservedPort(i: ManagedInstance, mine: Set<Int>): Boolean = i.reservation != null || i.status == InstanceStatus.RESERVED || i.port in mine

fun isRunningPort(i: ManagedInstance): Boolean = when (i.status) {
    InstanceStatus.RUNNING, InstanceStatus.DEGRADED, InstanceStatus.RESTARTING -> true
    // Some managers report a held port as "reserved" whether or not iperf3 is up: count it only if it says so.
    InstanceStatus.RESERVED -> i.desiredState.equals("running", ignoreCase = true) ||
        i.health?.lowercase() in setOf("ok", "healthy", "running", "up")
    else -> false
}

/** Free = the manager lists it as available (or, before that list loads, nobody holds it and it isn't blocked). */
fun isFreePort(i: ManagedInstance, ports: PortAvailability?, mine: Set<Int>): Boolean =
    ports?.available?.contains(i.port) ?: (!isReservedPort(i, mine) && i.status != InstanceStatus.BLOCKED)

fun matchesFilter(i: ManagedInstance, f: PortFilter, ports: PortAvailability?, mine: Set<Int>): Boolean = when (f) {
    PortFilter.ALL -> true
    PortFilter.FREE -> isFreePort(i, ports, mine)
    PortFilter.RESERVED -> isReservedPort(i, mine)
    PortFilter.RUNNING -> isRunningPort(i)
}

/** Works on the already-loaded list, so it's instant and offline. */
fun searchPorts(list: List<ManagedInstance>, query: PortQuery, filter: PortFilter, ports: PortAvailability?, mine: Set<Int>): List<ManagedInstance> {
    val byQuery = when (query) {
        PortQuery.All, is PortQuery.Invalid -> list
        is PortQuery.Range -> list.filter { it.port in query.from..query.to }
        is PortQuery.Single -> list.filter { it.port == query.port }.ifEmpty { list.filter { it.port.toString().startsWith(query.text) } }
    }
    return byQuery.filter { matchesFilter(it, filter, ports, mine) }
}

/** Shown when nothing matches, e.g. "Port 5400 isn't one of this server's ports (5201–5300)". */
fun noMatchText(list: List<ManagedInstance>, query: PortQuery, filter: PortFilter): String {
    val span = if (list.isEmpty()) "" else " (${list.minOf { it.port }}–${list.maxOf { it.port }})"
    val f = if (filter == PortFilter.ALL) "" else " ${filter.label.lowercase()}"
    return when (query) {
        is PortQuery.Single -> if (list.none { it.port == query.port || it.port.toString().startsWith(query.text) })
            "Port ${query.text} isn't one of this server's ports$span" else "Port ${query.text} isn't$f right now"
        is PortQuery.Range -> "No$f ports between ${query.from} and ${query.to}$span"
        else -> "No$f ports"
    }
}
