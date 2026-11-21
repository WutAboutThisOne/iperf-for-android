package local.net.iperf.ui

import local.net.iperf.data.Server
import local.net.iperf.data.ServerSecrets
import local.net.iperf.ui.servers.ServerForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormTest {
    private val pem = "-----BEGIN PUBLIC KEY-----\n" + "A".repeat(80) + "\n-----END PUBLIC KEY-----"
    private val base = Server("s1", "old", 5201, "")

    @Test fun webpageIsOptionalAndNormalised() {
        val (s, sec) = ServerForm(host = "iperf.example.com", webUrl = "iperf.example.com").toServer(base)!!
        assertEquals("https://iperf.example.com", s.webUrl)
        assertTrue(sec.isEmpty)
        assertEquals("", ServerForm(host = "h").toServer(base)!!.first.webUrl)
        assertTrue(ServerForm(host = "h", webUrl = "javascript:alert(1)").validate().webUrl)
    }

    @Test fun iperfLoginNeedsAllThreeAndAValidKey() {
        assertNotNull(ServerForm(host = "h", iperfUser = "u").validate().iperf)
        assertNotNull(ServerForm(host = "h", iperfUser = "u", iperfPassword = "p", iperfKey = pem.replace("PUBLIC KEY", "RSA PUBLIC KEY")).validate().iperf)
        val ok = ServerForm(host = "h", iperfUser = " u ", iperfPassword = "p", iperfKey = pem)
        assertNull(ok.validate().iperf)
        val sec = ok.toServer(base)!!.second
        assertEquals("u", sec.iperfUser); assertTrue(sec.hasIperfAuth); assertTrue(sec.iperfPublicKey.endsWith("-----END PUBLIC KEY-----\n"))
    }

    @Test fun managerUrlDefaultsToWebpageAndMustBeHttps() {
        val f = ServerForm(host = "iperf.example.com", webUrl = "https://iperf.example.com", managerEnabled = true)
        val s = f.toServer(base)!!.first
        assertEquals("", s.managerUrl); assertEquals("https://iperf.example.com", s.managerBaseUrl)
        assertNotNull(ServerForm(host = "h", managerEnabled = true).validate().managerUrl)
        assertTrue(ServerForm(host = "h", managerEnabled = true, managerUrl = "http://iperf.example.com").validate().managerUrl!!.contains("https"))
        assertEquals("https://api.example.net", ServerForm(host = "h", managerEnabled = true, managerUrl = "api.example.net").toServer(base)!!.first.managerBaseUrl)
        // Turning the switch off hides it without losing the URL.
        assertNull(f.copy(managerEnabled = false).toServer(base)!!.first.managerBaseUrl)
    }

    @Test fun separateApiLoginOnlyKeptWhenEnabled() {
        val f = ServerForm(host = "h", webUser = "w", webPassword = "wp", apiUser = "a", apiPassword = "ap")
        assertEquals("w" to "wp", f.toServer(base)!!.second.managerLogin)
        assertEquals("a" to "ap", f.copy(separateApiLogin = true).toServer(base)!!.second.managerLogin)
    }

    @Test fun roundTripFromSaved() {
        val sec = ServerSecrets("w", "wp", "u", "p", pem, "a", "ap")
        val s = Server("s1", "h", 5300, "L", "https://x.example", true, "https://api.example")
        val f = ServerForm.from(s, sec)
        assertTrue(f.separateApiLogin)
        val (s2, sec2) = f.toServer(s)!!
        assertEquals(s, s2)
        assertEquals(sec.copy(iperfPublicKey = sec2.iperfPublicKey), sec2)
        assertFalse(ServerForm(host = "", port = "70000").toServer(base) != null)
    }
}
