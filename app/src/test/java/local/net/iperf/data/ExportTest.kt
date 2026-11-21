package local.net.iperf.data

import local.net.iperf.SampleData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ExportTest {
    private val utc = ZoneId.of("UTC")

    @Test fun csvHeaderAndRows() {
        val csv = Export.csv(SampleData.history, utc).trimEnd().lines()
        assertEquals(Export.CSV_HEADER.joinToString(","), csv[0])
        assertEquals(6, csv.size)
        fun col(row: List<String>, name: String) = row[Export.CSV_HEADER.indexOf(name)]
        val row = csv[1].split(",")
        assertEquals(Export.CSV_HEADER.size, row.size)
        assertEquals("Home NAS", col(row, "server"))
        assertEquals("TCP", col(row, "protocol")); assertEquals("UPLOAD", col(row, "direction"))
        assertEquals("612.4", col(row, "upload_sender_mbps")); assertEquals("609.8", col(row, "upload_receiver_mbps"))
        assertEquals("37", col(row, "upload_retransmits"))
        assertEquals("766000000", col(row, "upload_bytes_sent")); assertEquals("762250000", col(row, "upload_bytes_received"))
        assertEquals("WIFI", col(row, "network")); assertEquals("HomeNet-5G", col(row, "ssid")); assertEquals("5 GHz", col(row, "band"))
        assertEquals("finished", col(row, "outcome"))
        assertTrue(row[0].endsWith("Z"))
        val udp = csv[2].split(",")
        assertEquals("200", col(udp, "udp_target_mbps")); assertEquals("1.62", col(udp, "download_loss_pct")); assertEquals("0.284", col(udp, "download_jitter_ms"))
        val failed = csv[5].split(",")
        assertEquals("failed", col(failed, "outcome"))
        assertEquals("Lost connection", col(failed, "error_title"))
    }

    @Test fun csvEscaping() {
        assertEquals("plain", Export.escape("plain"))
        assertEquals("\"a,b\"", Export.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Export.escape("say \"hi\""))
        assertEquals("'=HYPERLINK(1)", Export.escape("=HYPERLINK(1)"))
        val rec = SampleData.tcpUpload.copy(serverLabel = "Lab, rack 2", network = SampleData.wifi.copy(ssid = "Café \"5G\""))
        val line = Export.csv(listOf(rec), utc).lines()[1]
        assertTrue(line.contains("\"Lab, rack 2\""))
        assertTrue(line.contains("\"Café \"\"5G\"\"\""))
    }

    @Test fun jsonRoundTrips() {
        val obj = JSONObject(Export.json(SampleData.history))
        assertEquals("iPerf for Android", obj.getString("app"))
        val arr = obj.getJSONArray("results")
        assertEquals(5, arr.length())
        val back = (0 until arr.length()).map { Codec.record(arr.getJSONObject(it)) }
        assertEquals(SampleData.history, back)
        assertTrue(arr.getJSONObject(0).has("time"))
        assertEquals(10, arr.getJSONObject(0).getJSONArray("intervals").length())
    }

    @Test fun fileName() {
        assertTrue(Export.fileName("csv").matches(Regex("iperf-history-\\d{8}-\\d{4}\\.csv")))
    }
}
