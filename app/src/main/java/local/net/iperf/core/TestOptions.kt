package local.net.iperf.core

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

enum class Protocol(val label: String) { TCP("TCP"), UDP("UDP") }

enum class Direction(val label: String, val short: String) {
    UPLOAD("Upload", "Up"),
    DOWNLOAD("Download", "Down"),
    BIDIR("Both", "Both"),
}

const val DEFAULT_PORT = 5201
const val CONNECT_TIMEOUT_MS = 5000

/** A fully validated iperf3 client run. Build one with [OptionsDraft.toOptions]. */
data class TestOptions(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val protocol: Protocol = Protocol.TCP,
    val direction: Direction = Direction.UPLOAD,
    val durationSec: Int = 10,
    val parallel: Int = 1,
    /** UDP target bitrate in Mbit/s (0 = unlimited). Ignored for TCP. */
    val udpBandwidthMbps: Double = 100.0,
    val omitSec: Int = 0,
) {
    /** Duration 0 = run until stopped (iperf3 `-t 0`). */
    val endless: Boolean get() = durationSec == 0

    /**
     * iperf3 command-line arguments (without the executable). Always client mode with
     * `--json-stream` + `--forceflush` so every one-second interval arrives on stdout as one JSON line.
     */
    fun toArgs(): List<String> = buildList {
        add("-c"); add(host)
        add("-p"); add(port.toString())
        add("-t"); add(durationSec.toString())
        add("-i"); add("1")
        add("--json-stream")
        add("--forceflush")
        add("--connect-timeout"); add(CONNECT_TIMEOUT_MS.toString())
        if (protocol == Protocol.UDP) {
            add("-u")
            add("-b"); add(formatBandwidthArg(udpBandwidthMbps))
        }
        when (direction) {
            Direction.UPLOAD -> Unit
            Direction.DOWNLOAD -> add("-R")
            Direction.BIDIR -> add("--bidir")
        }
        if (parallel > 1) { add("-P"); add(parallel.toString()) }
        if (omitSec > 0) { add("-O"); add(omitSec.toString()) }
    }

    /** Short human summary, e.g. "TCP · Upload · 10 s · 4 streams". */
    fun describe(): String = buildList {
        add(protocol.label)
        add(direction.label)
        add(if (endless) "endless" else "$durationSec s")
        if (parallel > 1) add("$parallel streams")
        if (protocol == Protocol.UDP) add(if (udpBandwidthMbps == 0.0) "unlimited" else "${trimNumber(udpBandwidthMbps)} Mbps target")
        if (omitSec > 0) add("omit $omitSec s")
    }.joinToString(" · ")
}

/** iperf3 -b value: whole Mbit/s as "100M", fractions as kbit/s ("500K"), 0 = unlimited. */
fun formatBandwidthArg(mbps: Double): String = when {
    mbps <= 0.0 -> "0"
    mbps == floor(mbps) -> "${mbps.toLong()}M"
    else -> "${(mbps * 1000).roundToLong()}K"
}

fun trimNumber(v: Double): String =
    if (v == floor(v)) v.toLong().toString() else String.format(Locale.US, "%s", v).trimEnd('0').trimEnd('.')

object Limits {
    const val MAX_DURATION = 3600
    const val MAX_PARALLEL = 128
    const val MAX_OMIT = 60
    const val MAX_UDP_MBPS = 100_000.0
}

private val HOST_CHARS = Regex("^[A-Za-z0-9._:%-]+$")

/** Normalises a host field: trims, strips IPv6 brackets. Returns null if it isn't usable. */
fun normaliseHost(raw: String): String? {
    var h = raw.trim()
    if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
    if (h.isEmpty() || h.startsWith("-") || !HOST_CHARS.matches(h) || h.length > 253) return null
    return h
}

fun validatePort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

/** Editable form state for the test options; text fields stay strings until a run starts. */
data class OptionsDraft(
    val protocol: Protocol = Protocol.TCP,
    val direction: Direction = Direction.UPLOAD,
    val duration: String = "10",
    val parallel: String = "1",
    val udpBandwidth: String = "100",
    val omit: String = "0",
) {
    sealed interface Parsed {
        data class Ok(val options: TestOptions) : Parsed
        data class Invalid(val field: Field, val message: String) : Parsed
    }

    enum class Field { SERVER, DURATION, PARALLEL, BANDWIDTH, OMIT }

    fun toOptions(host: String?, port: Int?): Parsed {
        val h = host?.let(::normaliseHost) ?: return Parsed.Invalid(Field.SERVER, "Pick or add a server first")
        val p = port?.takeIf { it in 1..65535 } ?: return Parsed.Invalid(Field.SERVER, "Server port must be 1–65535")
        val d = duration.trim().toIntOrNull()?.takeIf { it in 0..Limits.MAX_DURATION }
            ?: return Parsed.Invalid(Field.DURATION, "Duration must be 0–${Limits.MAX_DURATION} s (0 = endless, runs until you press Stop)")
        val n = parallel.trim().ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 1..Limits.MAX_PARALLEL }
            ?: return Parsed.Invalid(Field.PARALLEL, "Streams must be 1–${Limits.MAX_PARALLEL}")
        val o = omit.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..Limits.MAX_OMIT }
            ?: return Parsed.Invalid(Field.OMIT, "Omit must be 0–${Limits.MAX_OMIT} s")
        if (d > 0 && o >= d) return Parsed.Invalid(Field.OMIT, "Omit must be shorter than the duration")
        var bw = 100.0
        if (protocol == Protocol.UDP) {
            bw = udpBandwidth.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 && it <= Limits.MAX_UDP_MBPS }
                ?: return Parsed.Invalid(Field.BANDWIDTH, "UDP bandwidth must be 0–${Limits.MAX_UDP_MBPS.toLong()} Mbps (0 = unlimited)")
        }
        return Parsed.Ok(TestOptions(h, p, protocol, direction, d, n, bw, o))
    }
}
