package local.net.iperf.core

enum class NetType(val label: String) {
    WIFI("Wi-Fi"), CELLULAR("Cellular"), ETHERNET("Ethernet"), VPN("VPN"), OTHER("Other"), NONE("Offline")
}

/** What the phone was connected to when a test ran. Every field is best-effort. */
data class NetworkSnapshot(
    val type: NetType = NetType.NONE,
    /** null when unknown; Android hides it without location permission. */
    val ssid: String? = null,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
    val linkMbps: Int? = null,
    /** "Wi-Fi 6" etc. when the platform reports it. */
    val wifiStandard: String? = null,
    /** e.g. "LTE", "5G NSA" (only where Android exposes it without phone permission). */
    val cellularType: String? = null,
    val carrier: String? = null,
    /** Also over a VPN (the underlying transport is still in [type]). */
    val vpn: Boolean = false,
) {
    val band: String? get() = frequencyMhz?.let(::bandFor)

    fun describe(): String = buildList {
        add(type.label)
        when (type) {
            NetType.WIFI -> {
                ssid?.let(::add)
                band?.let(::add)
                rssiDbm?.let { add("$it dBm") }
                linkMbps?.let { add("link $it Mbps") }
            }
            NetType.CELLULAR -> {
                cellularType?.let(::add)
                carrier?.takeIf { it.isNotBlank() }?.let(::add)
            }
            else -> Unit
        }
        if (vpn && type != NetType.VPN) add("VPN")
    }.joinToString(" · ")
}

fun bandFor(mhz: Int): String? = when (mhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5899 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    in 57000..71000 -> "60 GHz"
    else -> null
}

/** Rough RSSI quality bucket for a label. */
fun rssiQuality(dbm: Int): String = when {
    dbm >= -55 -> "excellent"
    dbm >= -67 -> "good"
    dbm >= -75 -> "fair"
    else -> "weak"
}
