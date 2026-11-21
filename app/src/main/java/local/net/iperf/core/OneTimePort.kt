package local.net.iperf.core

/** A port used for the next run(s) of one server instead of its saved port. Never written to the saved server. */
data class OneTimePort(val serverId: String, val port: Int, val reserved: Boolean = false, val reserveMinutes: Int = 0)

/** What the port prompt resolves to for a server whose saved port is [savedPort]. */
fun resolvePortChoice(serverId: String, savedPort: Int, chosen: Int, reserve: Boolean, minutes: Int = 15): OneTimePort? =
    if (chosen == savedPort && !reserve) null else OneTimePort(serverId, chosen, reserve, if (reserve) minutes else 0)

/** Port a run should use: the one-time port when it belongs to [serverId], otherwise the saved port. */
fun effectivePort(serverId: String, savedPort: Int, oneTime: OneTimePort?): Int =
    oneTime?.takeIf { it.serverId == serverId }?.port ?: savedPort
