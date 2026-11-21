package local.net.iperf

import local.net.iperf.core.Direction
import local.net.iperf.core.FlowSummary
import local.net.iperf.core.IntervalPoint
import local.net.iperf.core.NetType
import local.net.iperf.core.NetworkSnapshot
import local.net.iperf.core.Protocol
import local.net.iperf.core.Summary
import local.net.iperf.core.TestOptions
import local.net.iperf.data.Server
import local.net.iperf.data.TestRecord
import java.util.Calendar
import kotlin.math.sin

/** Example data for tests and screenshots (not real measurements). */
object SampleData {
    fun t(day: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, day, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val servers = listOf(
        Server("s1", "192.168.1.20", 5201, "Home NAS"),
        Server("s2", "desktop.lan", 5201, "Office desktop"),
        Server("s3", "iperf.example.net", 5209, ""),
        Server("s4", "fd00::12", 5201, "Router (IPv6)"),
    )

    val wifi = NetworkSnapshot(NetType.WIFI, "HomeNet-5G", 5500, -54, 1201, "Wi-Fi 6")
    val wifiNoSsid = NetworkSnapshot(NetType.WIFI, null, 2437, -71, 144, "Wi-Fi 4")
    val cell = NetworkSnapshot(NetType.CELLULAR, cellularType = "5G NSA", carrier = "T-Mobile")

    fun wave(n: Int, base: Double, amp: Double, phase: Double = 0.0) = (1..n).map { base + amp * sin(it * 0.9 + phase) + (it % 3) * amp * 0.15 }

    fun points(n: Int, up: List<Double>?, down: List<Double>?) = (0 until n).map {
        IntervalPoint((it + 1).toDouble(), up?.get(it), down?.get(it))
    }

    val tcpUpload = TestRecord(
        id = "r1", timestamp = t(17, 12, 41), serverLabel = "Home NAS",
        options = TestOptions("192.168.1.20", durationSec = 10, parallel = 4),
        summary = Summary(upload = FlowSummary(612.4, 609.8, 766_000_000, 762_250_000, retransmits = 37), durationSec = 10.0),
        points = points(10, wave(10, 610.0, 35.0), null),
        network = wifi, iperfVersion = "iperf 3.22",
    )
    val udpDownload = TestRecord(
        id = "r2", timestamp = t(17, 9, 5), serverLabel = "Office desktop",
        options = TestOptions("desktop.lan", protocol = Protocol.UDP, direction = Direction.DOWNLOAD, durationSec = 10, udpBandwidthMbps = 200.0),
        summary = Summary(download = FlowSummary(200.0, 196.7, 250_000_000, 245_900_000, jitterMs = 0.284, lostPercent = 1.62, lostPackets = 279, packets = 17_240), durationSec = 10.0),
        points = points(10, null, wave(10, 196.0, 4.0)),
        network = wifiNoSsid, iperfVersion = "iperf 3.22",
    )
    val bidir = TestRecord(
        id = "r3", timestamp = t(16, 18, 22), serverLabel = "iperf.example.net",
        options = TestOptions("iperf.example.net", 5209, direction = Direction.BIDIR, durationSec = 20),
        summary = Summary(upload = FlowSummary(48.3, 47.9, retransmits = 112), download = FlowSummary(312.0, 309.6, retransmits = 840), durationSec = 20.0),
        points = points(20, wave(20, 48.0, 6.0), wave(20, 305.0, 40.0, 1.3)),
        network = cell, iperfVersion = "iperf 3.22",
    )
    val stopped = TestRecord(
        id = "r4", timestamp = t(15, 21, 3), serverLabel = "Home NAS",
        options = TestOptions("192.168.1.20", direction = Direction.DOWNLOAD, durationSec = 30),
        summary = Summary(download = FlowSummary(null, 884.1), durationSec = 7.0),
        points = points(7, null, wave(7, 880.0, 20.0)),
        network = wifi, stoppedEarly = true, iperfVersion = "iperf 3.22",
    )
    val failedLost = TestRecord(
        id = "r5", timestamp = t(17, 18, 12), serverLabel = "Lab server",
        options = TestOptions("192.0.2.10", 5292, durationSec = 10),
        summary = Summary(upload = FlowSummary(420.0, null), durationSec = 4.0),
        points = points(4, wave(4, 420.0, 30.0), null),
        network = wifi, iperfVersion = "iperf 3.22",
        errorTitle = "Lost connection",
        errorDetail = "The connection to 192.0.2.10:5292 dropped before the test finished. Partial results from the first 4 s are saved.",
    )
    val history = listOf(tcpUpload, udpDownload, bidir, stopped, failedLost)
}
