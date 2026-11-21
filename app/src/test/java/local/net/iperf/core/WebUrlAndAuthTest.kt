package local.net.iperf.core

import local.net.iperf.run.buildCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlAndAuthTest {
    @Test fun webUrlNormalisation() {
        assertEquals("", normaliseWebUrl("  "))
        assertEquals("https://iperf.example.com", normaliseWebUrl("iperf.example.com"))
        assertEquals("https://iperf.example.com/status", normaliseWebUrl(" iperf.example.com/status "))
        assertEquals("http://192.168.1.20:8080/", normaliseWebUrl("http://192.168.1.20:8080/"))
        assertEquals("https://nas.lan:5000", normaliseWebUrl("nas.lan:5000"))
        assertEquals("https://example.com", normaliseWebUrl("HTTPS://example.com"))
        assertNull(normaliseWebUrl("javascript:alert(1)"))
        assertNull(normaliseWebUrl("ftp://example.com"))
        assertNull(normaliseWebUrl("file:///etc/passwd"))
        assertNull(normaliseWebUrl("exa mple.com"))
        assertNull(normaliseWebUrl("https://"))
    }

    private val pem = "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAu1SU1LfVLPHCozMxH2Mo\n" +
        "4lgOEePzNm0tRgeLezV6ffAt0gunVTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u\n" +
        "-----END PUBLIC KEY-----"

    @Test fun publicKeyChecks() {
        val ok = PublicKeyPem.check("  $pem\r\n") as PublicKeyPem.Check.Ok
        assertTrue(ok.pem.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertTrue(ok.pem.endsWith("-----END PUBLIC KEY-----\n"))
        assertTrue(PublicKeyPem.check("") is PublicKeyPem.Check.Invalid)
        assertTrue(PublicKeyPem.check("hello") is PublicKeyPem.Check.Invalid)
        val pkcs1 = PublicKeyPem.check(pem.replace("PUBLIC KEY", "RSA PUBLIC KEY")) as PublicKeyPem.Check.Invalid
        assertTrue(pkcs1.message.contains("-pubout"))
        assertTrue(PublicKeyPem.check("-----BEGIN PUBLIC KEY-----\nAA!!\n-----END PUBLIC KEY-----") is PublicKeyPem.Check.Invalid)
    }

    @Test fun authArgsNeverContainPassword() {
        val auth = IperfAuth("alice", "s3cret-pass!", pem)
        val cmd = buildCommand("/bin/iperf3", TestOptions("10.0.0.2", 5202), auth, "/data/keys/k.pem")
        val i = cmd.indexOf("--username")
        assertEquals("alice", cmd[i + 1])
        assertEquals("/data/keys/k.pem", cmd[cmd.indexOf("--rsa-public-key-path") + 1])
        assertFalse(cmd.any { "s3cret" in it })
        assertFalse(auth.toString().contains("s3cret"))
        assertFalse(auth.toString().contains("MIIB"))
        // No auth → no auth flags.
        val plain = buildCommand("/bin/iperf3", TestOptions("10.0.0.2"), null, null)
        assertFalse("--username" in plain)
        assertEquals("5201", plain[plain.indexOf("-p") + 1])
    }

    @Test fun authErrorsAreMapped() {
        assertEquals("Login rejected", ErrorMapper.map("test authorization failed", "h", 5201).title)
        assertEquals("Login details incomplete",
            ErrorMapper.map("you must specify a username, password, and path to a valid RSA public key", "h", 5201).title)
        assertEquals("Login not supported", ErrorMapper.map("unrecognized option '--username'", "h", 5201).title)
        // What a real iperf3 3.22 server does on a bad login (verified against an auth-enabled server): it just hangs up.
        assertEquals("Login rejected", ErrorMapper.map("control socket has closed unexpectedly", "h", 5201, usedLogin = true).title)
        val noLogin = ErrorMapper.map("control socket has closed unexpectedly", "h", 5201)
        assertEquals("Server closed the connection", noLogin.title)
        assertTrue(noLogin.detail.contains("requires an iperf3 login"))
        // Existing mappings still win for their messages.
        assertEquals("Server busy", ErrorMapper.map("the server is busy running a test. try again later", "h", 5201).title)
    }

    @Test fun oneTimePortChoice() {
        assertNull(resolvePortChoice("s1", 5201, 5201, reserve = false))
        assertEquals(OneTimePort("s1", 5202), resolvePortChoice("s1", 5201, 5202, reserve = false))
        assertEquals(OneTimePort("s1", 5201, true, 15), resolvePortChoice("s1", 5201, 5201, reserve = true))
        assertEquals(5202, effectivePort("s1", 5201, OneTimePort("s1", 5202)))
        assertEquals(5201, effectivePort("s2", 5201, OneTimePort("s1", 5202)))
        assertEquals(5201, effectivePort("s1", 5201, null))
    }
}
