package local.net.iperf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestOptionsTest {
    private val base = listOf("-c", "nas.lan", "-p", "5201", "-t", "10", "-i", "1", "--json-stream", "--forceflush", "--connect-timeout", "5000")

    @Test fun tcpUploadDefaults() {
        assertEquals(base, TestOptions("nas.lan").toArgs())
    }

    @Test fun tcpDownloadParallelOmit() {
        val args = TestOptions("nas.lan", direction = Direction.DOWNLOAD, parallel = 4, omitSec = 2).toArgs()
        assertEquals(base + listOf("-R", "-P", "4", "-O", "2"), args)
    }

    @Test fun udpBidirWithBandwidth() {
        val args = TestOptions("10.0.0.2", 5202, Protocol.UDP, Direction.BIDIR, 30, 1, 250.0).toArgs()
        assertEquals(
            listOf("-c", "10.0.0.2", "-p", "5202", "-t", "30", "-i", "1", "--json-stream", "--forceflush", "--connect-timeout", "5000",
                "-u", "-b", "250M", "--bidir"),
            args,
        )
    }

    @Test fun tcpIgnoresUdpBandwidth() {
        assertFalse(TestOptions("h", udpBandwidthMbps = 5.0).toArgs().contains("-b"))
        assertFalse(TestOptions("h").toArgs().contains("-u"))
    }

    @Test fun bandwidthFormatting() {
        assertEquals("0", formatBandwidthArg(0.0))
        assertEquals("100M", formatBandwidthArg(100.0))
        assertEquals("500K", formatBandwidthArg(0.5))
        assertEquals("1500K", formatBandwidthArg(1.5))
    }

    @Test fun hostNormalisation() {
        assertEquals("fe80::1%wlan0", normaliseHost("  [fe80::1%wlan0] "))
        assertEquals("iperf.example.net", normaliseHost("iperf.example.net"))
        assertNull(normaliseHost(""))
        assertNull(normaliseHost("-s"))            // would be parsed as an option
        assertNull(normaliseHost("host name"))
        assertNull(normaliseHost("a;rm -rf /"))
    }

    @Test fun portValidation() {
        assertEquals(5201, validatePort("5201"))
        assertNull(validatePort("0"))
        assertNull(validatePort("70000"))
        assertNull(validatePort("abc"))
    }

    @Test fun draftParsesToOptions() {
        val d = OptionsDraft(Protocol.UDP, Direction.DOWNLOAD, " 20 ", "2", "12,5", "3")
        val ok = d.toOptions("[::1]", 5201) as OptionsDraft.Parsed.Ok
        assertEquals(TestOptions("::1", 5201, Protocol.UDP, Direction.DOWNLOAD, 20, 2, 12.5, 3), ok.options)
        assertTrue(ok.options.toArgs().containsAll(listOf("-u", "-b", "12500K", "-R", "-P", "2", "-O", "3")))
    }

    @Test fun draftValidationErrors() {
        fun field(d: OptionsDraft, host: String? = "h", port: Int? = 5201) = (d.toOptions(host, port) as OptionsDraft.Parsed.Invalid).field
        assertEquals(OptionsDraft.Field.SERVER, field(OptionsDraft(), host = null))
        assertEquals(OptionsDraft.Field.SERVER, field(OptionsDraft(), port = 0))
        assertEquals(OptionsDraft.Field.DURATION, field(OptionsDraft(duration = "-1")))
        assertEquals(OptionsDraft.Field.DURATION, field(OptionsDraft(duration = "9999")))
        assertEquals(OptionsDraft.Field.PARALLEL, field(OptionsDraft(parallel = "500")))
        assertEquals(OptionsDraft.Field.OMIT, field(OptionsDraft(duration = "5", omit = "5")))
        assertEquals(OptionsDraft.Field.BANDWIDTH, field(OptionsDraft(protocol = Protocol.UDP, udpBandwidth = "x")))
        // TCP ignores a bad UDP field.
        assertTrue(OptionsDraft(udpBandwidth = "x").toOptions("h", 1) is OptionsDraft.Parsed.Ok)
    }

    @Test fun describe() {
        assertEquals("UDP · Both · 30 s · 4 streams · 250 Mbps target · omit 2 s",
            TestOptions("h", protocol = Protocol.UDP, direction = Direction.BIDIR, durationSec = 30, parallel = 4, udpBandwidthMbps = 250.0, omitSec = 2).describe())
    }
}
