package local.net.iperf.core

import local.net.iperf.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real `iperf3 3.22 --json-stream` output captured against a local server. */
class IperfJsonTest {
    private fun events(name: String) = IperfJson.parseAll(Fixtures.text(name))
    private fun summary(name: String) = events(name).filterIsInstance<IperfEvent.Finished>().single().summary
    private fun points(name: String) = events(name).filterIsInstance<IperfEvent.Interval>().map { it.point }

    @Test fun tcpUpload() {
        val ev = events("tcp_upload.jsonl")
        val start = ev.first() as IperfEvent.Started
        assertEquals("iperf 3.22", start.version)
        assertEquals("127.0.0.1", start.remoteHost)
        assertEquals(5299, start.remotePort)
        assertEquals("TCP", start.protocol)
        assertEquals(3, start.durationSec)

        val pts = points("tcp_upload.jsonl")
        assertEquals(3, pts.size)
        assertTrue(pts.all { it.upMbps != null && it.downMbps == null })
        assertEquals(65459.38, pts[0].upMbps!!, 0.01)
        assertEquals(1.00077, pts[0].endSec, 1e-6)

        val s = summary("tcp_upload.jsonl")
        assertNull(s.download)
        val up = s.upload!!
        assertNotNull(up.senderMbps); assertNotNull(up.receiverMbps)
        assertNotNull(up.retransmits)
        assertNull(up.jitterMs); assertNull(up.lostPercent)
    }

    @Test fun tcpDownloadParallelIsDownload() {
        val pts = points("tcp_download_p2.jsonl")
        assertTrue(pts.all { it.downMbps != null && it.upMbps == null })
        val s = summary("tcp_download_p2.jsonl")
        assertNull(s.upload)
        assertEquals(130132.39, s.download!!.senderMbps!!, 0.01)
        assertEquals(130142.07, s.download!!.receiverMbps!!, 0.01)
        assertEquals(2, s.download!!.retransmits)
        assertEquals(48823140352L, s.download!!.bytesReceived)
    }

    @Test fun udpUploadHasJitterAndLoss() {
        val s = summary("udp_upload.jsonl")
        val up = s.upload!!
        assertNull(s.download)
        assertNull(up.retransmits)
        assertEquals(0.0509, up.jitterMs!!, 0.0001)
        assertEquals(0.0, up.lostPercent!!, 0.0)
        assertEquals(573L, up.packets)
        assertEquals(50.04, up.receiverMbps!!, 0.01)
    }

    @Test fun tcpBidirBothDirections() {
        val pts = points("tcp_bidir.jsonl")
        assertTrue(pts.all { it.upMbps != null && it.downMbps != null })
        val s = summary("tcp_bidir.jsonl")
        assertEquals(64741.31, s.upload!!.receiverMbps!!, 0.01)
        assertEquals(64301.30, s.download!!.receiverMbps!!, 0.01)
        assertEquals(0, s.download!!.retransmits)
    }

    @Test fun udpBidir() {
        val s = summary("udp_bidir.jsonl")
        assertEquals(19.997, s.upload!!.receiverMbps!!, 0.001)
        assertEquals(19.997, s.download!!.receiverMbps!!, 0.001)
        assertEquals(0.05138, s.download!!.jitterMs!!, 0.0001)
        assertNull(s.upload!!.retransmits)
    }

    @Test fun errorsAreReported() {
        for (f in listOf("refused.jsonl", "busy.jsonl", "timeout.jsonl")) {
            val ev = events(f)
            assertTrue(f, ev.first() is IperfEvent.Error)
            assertTrue("empty end has no summary: $f", ev.none { it is IperfEvent.Finished })
        }
        assertEquals("the server is busy running a test. try again later", (events("busy.jsonl").first() as IperfEvent.Error).message)
    }

    @Test fun stoppedRunStillHasPartialSummary() {
        val ev = events("tcp_stopped_sigterm.jsonl")
        assertTrue(ev.any { it is IperfEvent.Error && "interrupt" in it.message })
        val s = summary("tcp_stopped_sigterm.jsonl")
        assertEquals(2.4995, s.durationSec!!, 0.001)
        assertNotNull(s.upload!!.senderMbps)
    }

    @Test fun ignoresNoise() {
        assertNull(IperfJson.parseLine(""))
        assertNull(IperfJson.parseLine("Connecting to host 1.2.3.4, port 5201"))
        assertNull(IperfJson.parseLine("{\"event\":\"interval\",\"data\":{\"sum\":"))
        assertNull(IperfJson.parseLine("{\"event\":\"something_new\",\"data\":{}}"))
    }

    @Test fun omittedIntervalFlag() {
        val p = (IperfJson.parseLine("""{"event":"interval","data":{"sum":{"end":1.0,"bits_per_second":2e7,"omitted":true,"sender":true}}}""") as IperfEvent.Interval).point
        assertTrue(p.omitted)
        assertEquals(20.0, p.upMbps!!, 1e-9)
    }

    @Test fun summaryFromIntervalsSkipsOmitted() {
        val s = summaryFromIntervals(listOf(IntervalPoint(1.0, 1000.0, omitted = true), IntervalPoint(2.0, 100.0), IntervalPoint(3.0, 200.0)))!!
        assertEquals(150.0, s.upload!!.senderMbps!!, 1e-9)
        assertEquals(200.0, s.upload!!.peakMbps!!, 1e-9)
        assertEquals(100.0, s.upload!!.minMbps!!, 1e-9)
        assertNotNull(s.upload!!.bytesSent)
        assertNull(s.download)
        assertNull(summaryFromIntervals(emptyList()))
    }

    @Test fun enrichKeepsOfficialBytesAndAddsPeakMin() {
        val points = listOf(IntervalPoint(1.0, 100.0), IntervalPoint(2.0, 300.0), IntervalPoint(3.0, 200.0))
        val official = Summary(upload = FlowSummary(senderMbps = 200.0, receiverMbps = 198.0, bytesSent = 75_000_000, bytesReceived = 74_000_000, retransmits = 4))
        val e = official.enriched(points).upload!!
        assertEquals(200.0, e.senderMbps!!, 1e-9)
        assertEquals(75_000_000L, e.bytesSent)
        assertEquals(74_000_000L, e.bytesReceived)
        assertEquals(4, e.retransmits)
        assertEquals(300.0, e.peakMbps!!, 1e-9)
        assertEquals(100.0, e.minMbps!!, 1e-9)
    }

    @Test fun headlinePrefersSenderWhenTcpReceiverIsZero() {
        // iperf3 reports receiver_mbps=0 if the server dies before it acks; use the sender rate.
        val dead = FlowSummary(senderMbps = 39_590.77, receiverMbps = 0.0, bytesSent = 19_798_949_888, bytesReceived = 0, retransmits = 0)
        assertEquals(39_590.77, dead.mbps!!, 1e-9)
        val ok = FlowSummary(senderMbps = 200.0, receiverMbps = 198.0)
        assertEquals(198.0, ok.mbps!!, 1e-9)
        val udpLost = FlowSummary(senderMbps = 200.0, receiverMbps = 0.0, lostPercent = 100.0, lostPackets = 100, packets = 100)
        assertEquals(0.0, udpLost.mbps!!, 1e-9)
        val senderOnly = FlowSummary(senderMbps = 50.0, receiverMbps = null)
        assertEquals(50.0, senderOnly.mbps!!, 1e-9)
    }
}
