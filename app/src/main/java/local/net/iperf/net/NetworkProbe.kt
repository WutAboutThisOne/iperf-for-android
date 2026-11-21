package local.net.iperf.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import local.net.iperf.core.NetType
import local.net.iperf.core.NetworkSnapshot
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Best-effort description of the active network. Needs only ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE;
 * the Wi-Fi name additionally needs fine location (granted + location services on), otherwise it's null.
 */
class NetworkProbe(private val context: Context) {

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(): Boolean = runCatching {
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    }.getOrDefault(false)

    /** True when on Wi-Fi but Android is hiding the SSID from us. */
    fun ssidHidden(): Boolean {
        val s = basic()
        return s.type == NetType.WIFI && s.ssid == null
    }

    suspend fun snapshot(): NetworkSnapshot {
        val s = basic()
        if (s.type != NetType.CELLULAR) return s
        return s.copy(cellularType = cellularType())
    }

    @Suppress("DEPRECATION")
    fun basic(): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetworkSnapshot(NetType.NONE)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.ETHERNET
            vpn -> underlyingType(cm) ?: NetType.VPN
            else -> NetType.OTHER
        }
        var snap = NetworkSnapshot(type = type, vpn = vpn)
        if (type == NetType.WIFI) {
            val info: WifiInfo? = (if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo else null)
                ?: runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo }.getOrNull()
            // On 31+ the caps' WifiInfo has the SSID redacted; WifiManager still returns it with location permission.
            val wm = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo }.getOrNull()
            val ssid = cleanSsid(wm?.ssid) ?: cleanSsid(info?.ssid)
            val src = wm?.takeIf { it.networkId != -1 || it.rssi > -127 } ?: info
            snap = snap.copy(
                ssid = ssid,
                frequencyMhz = src?.frequency?.takeIf { it > 0 },
                rssiDbm = src?.rssi?.takeIf { it in -126..0 },
                linkMbps = src?.linkSpeed?.takeIf { it > 0 },
                wifiStandard = if (Build.VERSION.SDK_INT >= 30) src?.wifiStandard?.let(::wifiStandardName) else null,
            )
        }
        if (type == NetType.CELLULAR) {
            snap = snap.copy(carrier = runCatching {
                (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
            }.getOrNull()?.takeIf { it.isNotBlank() })
        }
        return snap
    }

    private fun underlyingType(cm: ConnectivityManager): NetType? = cm.allNetworks.asSequence()
        .mapNotNull { cm.getNetworkCapabilities(it) }
        .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        .firstNotNullOfOrNull {
            when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.ETHERNET
                else -> null
            }
        }

    /**
     * LTE / 5G NSA / 5G SA from TelephonyDisplayInfo. On Android 12+ that listener needs no permission;
     * anything that would need READ_PHONE_STATE (deliberately not requested) just yields null.
     */
    private suspend fun cellularType(): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return withTimeoutOrNull(800) {
            suspendCancellableCoroutine { cont ->
                val exec = Executor { it.run() }
                val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        runCatching { tm.unregisterTelephonyCallback(this) }
                        if (cont.isActive) cont.resume(displayName(info))
                    }
                }
                try {
                    tm.registerTelephonyCallback(exec, cb)
                    cont.invokeOnCancellation { runCatching { tm.unregisterTelephonyCallback(cb) } }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    companion object {
        fun cleanSsid(raw: String?): String? {
            val s = raw?.trim()?.removeSurrounding("\"") ?: return null
            return s.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || it == "<unknown ssid>" || it == "0x" }
        }

        fun wifiStandardName(std: Int): String? = when (std) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
            8 -> "Wi-Fi 7" // WIFI_STANDARD_11BE (API 33)
            else -> null
        }

        fun displayName(info: TelephonyDisplayInfo): String? = when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED, 5 /* NR_NSA_MMWAVE (deprecated) */ -> "5G+"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE CA"
            else -> networkTypeName(info.networkType)
        }

        fun networkTypeName(t: Int): String? = when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            else -> null
        }
    }
}
