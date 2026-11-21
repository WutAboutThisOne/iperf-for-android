package local.net.iperf.data

import android.content.Context
import local.net.iperf.core.Direction
import local.net.iperf.core.OptionsDraft
import local.net.iperf.core.Protocol

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var draft: OptionsDraft
        get() = OptionsDraft(
            protocol = Protocol.entries.firstOrNull { it.name == sp.getString("protocol", null) } ?: Protocol.TCP,
            direction = Direction.entries.firstOrNull { it.name == sp.getString("direction", null) } ?: Direction.UPLOAD,
            duration = sp.getString("duration", null) ?: "10",
            parallel = sp.getString("parallel", null) ?: "1",
            udpBandwidth = sp.getString("udp_bw", null) ?: "100",
            omit = sp.getString("omit", null) ?: "0",
        )
        set(d) {
            sp.edit().putString("protocol", d.protocol.name).putString("direction", d.direction.name)
                .putString("duration", d.duration).putString("parallel", d.parallel)
                .putString("udp_bw", d.udpBandwidth).putString("omit", d.omit).apply()
        }

    var selectedServerId: String?
        get() = sp.getString("server", null)
        set(v) = sp.edit().putString("server", v).apply()

    var ssidHintDismissed: Boolean
        get() = sp.getBoolean("ssid_hint_dismissed", false)
        set(v) = sp.edit().putBoolean("ssid_hint_dismissed", v).apply()

    var locationAsked: Boolean
        get() = sp.getBoolean("location_asked", false)
        set(v) = sp.edit().putBoolean("location_asked", v).apply()

    var notificationsAsked: Boolean
        get() = sp.getBoolean("notifications_asked", false)
        set(v) = sp.edit().putBoolean("notifications_asked", v).apply()

    var autoStartTest: Boolean
        get() = sp.getBoolean("start_and_test_auto", true)
        set(v) = sp.edit().putBoolean("start_and_test_auto", v).apply()

    /** Servers whose manager refused to extend a held reservation (HTTP 409); Extend is hidden for them. */
    var extendUnsupported: Set<String>
        get() = sp.getStringSet("extend_unsupported", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("extend_unsupported", v.toSet()).apply()
}
