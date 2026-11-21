package local.net.iperf.core

import local.net.iperf.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsAndFormatTest {
    private fun title(raw: String) = ErrorMapper.map(raw, "nas.lan", 5201).title

    @Test fun mapsRealIperfErrors() {
        fun firstError(f: String) = (IperfJson.parseAll(Fixtures.text(f)).first() as IperfEvent.Error).message
        assertEquals("Connection refused", title(firstError("refused.jsonl")))
        assertEquals("Server busy", title(firstError("busy.jsonl")))
        assertEquals("Server unreachable", title(firstError("timeout.jsonl")))
        assertTrue(ErrorMapper.map(firstError("refused.jsonl"), "nas.lan", 5201).detail.contains("nas.lan:5201"))
    }

    @Test fun mapsOtherErrors() {
        assertEquals("Can't find host", title("unable to connect to server: Name or service not known"))
        assertEquals("Can't find host", title("error - unable to resolve host"))
        // Bionic's gai_strerror(EAI_NONAME) wording.
        assertEquals("Can't find host", title("unable to connect to server - server may have stopped running or use a different port, firewall issue, etc.: hostname nor servname provided, or not known"))
        assertEquals("Can't connect", title("unable to connect to server - server may have stopped running or use a different port, firewall issue, etc.: Bad file descriptor"))
        assertEquals("No route to server", title("unable to connect to server: No route to host"))
        assertEquals("Not an iperf3 server", title("received an unknown control message"))
        assertEquals("Connection dropped", title("control socket: Connection reset by peer"))
        assertEquals("Test failed", title("something odd"))
    }

    @Test fun formatsThroughput() {
        assertEquals("—", formatMbps(null))
        assertEquals("512 kbps", formatMbps(0.512))
        assertEquals("87.3 Mbps", formatMbps(87.34))
        assertEquals("941 Mbps", formatMbps(941.2))
        assertEquals("2.37 Gbps", formatMbps(2371.0))
        assertEquals("941" to "Mbps", splitMbps(941.2))
        assertEquals("100 Mbps", formatMbps(99.97))
        assertEquals("1.00 Gbps", formatMbps(999.7))
    }

    @Test fun formatsOthers() {
        assertEquals("0%", formatPercent(0.0))
        assertEquals("0.42%", formatPercent(0.4213))
        assertEquals("<0.01%", formatPercent(0.001))
        assertEquals("0.051 ms", formatMs(0.0509))
        assertEquals("1.2 GB", formatBytes(1_234_567_890))
        assertEquals("—", formatBytes(null))
    }

    @Test fun networkDescriptions() {
        assertEquals("2.4 GHz", bandFor(2437)); assertEquals("5 GHz", bandFor(5180)); assertEquals("6 GHz", bandFor(5955))
        assertEquals(null, bandFor(900))
        assertEquals("Wi-Fi · HomeNet · 5 GHz · -52 dBm · link 866 Mbps",
            NetworkSnapshot(NetType.WIFI, "HomeNet", 5180, -52, 866).describe())
        assertEquals("Wi-Fi · 5 GHz · -60 dBm", NetworkSnapshot(NetType.WIFI, null, 5180, -60).describe())
        assertEquals("Cellular · 5G NSA · T-Mobile · VPN",
            NetworkSnapshot(NetType.CELLULAR, cellularType = "5G NSA", carrier = "T-Mobile", vpn = true).describe())
        assertEquals("good", rssiQuality(-60))
    }
}
