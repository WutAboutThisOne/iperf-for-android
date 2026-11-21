package local.net.iperf.data

import local.net.iperf.SampleData
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same AES-GCM layout as KeystoreCipher, with an in-memory key (the Android Keystore isn't available on the JVM). */
class TestCipher(seed: Byte = 7) : SecretCipher {
    private val key = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

class SecretsAndMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = ServerSecrets(
        webUser = "example-user", webPassword = "Example-Pass-123",
        iperfUser = "tester", iperfPassword = "iperf-Example-456", iperfPublicKey = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n",
        apiUser = "api-user", apiPassword = "api-Example-789",
    )

    @Test fun secretsRoundTripEncryptedAtRest() {
        val f = File(tmp.root, "secrets.json")
        val store = SecretStore(f, TestCipher())
        store.put("s1", fake)
        store.put("s2", ServerSecrets(webUser = "u", webPassword = "p"))
        val raw = f.readText()
        listOf("example-user", "Example-Pass-123", "iperf-Example-456", "api-Example-789", "BEGIN PUBLIC KEY", "tester").forEach {
            assertFalse("plaintext '$it' found on disk", raw.contains(it))
        }
        val again = SecretStore(f, TestCipher())
        assertEquals(fake, again.get("s1"))
        assertEquals("u", again.get("s2").webUser)
        assertEquals(ServerSecrets(), again.get("missing"))
        assertFalse(fake.toString().contains("Example-Pass"))
    }

    @Test fun emptySecretsAndDeleteRemoveEntry() {
        val f = File(tmp.root, "secrets.json")
        val store = SecretStore(f, TestCipher())
        store.put("s1", fake)
        store.put("s1", ServerSecrets())
        assertTrue(store.items.value.isEmpty())
        store.put("s2", fake); store.delete("s2")
        assertTrue(SecretStore(f, TestCipher()).items.value.isEmpty())
    }

    @Test fun wrongKeyDropsSecretsInsteadOfCrashing() {
        val f = File(tmp.root, "secrets.json")
        SecretStore(f, TestCipher(1)).put("s1", fake)
        assertTrue(SecretStore(f, TestCipher(2)).items.value.isEmpty())
        // And tampering is detected (GCM tag), not silently decoded.
        val o = org.json.JSONObject(f.readText())
        val blob = java.util.Base64.getDecoder().decode(o.getString("s1"))
        blob[blob.size - 1] = (blob.last().toInt() xor 1).toByte()
        f.writeText(o.put("s1", java.util.Base64.getEncoder().encodeToString(blob)).toString())
        assertTrue(SecretStore(f, TestCipher(1)).items.value.isEmpty())
    }

    @Test fun managerLoginFallsBackToWebpageLogin() {
        assertEquals("api-user" to "api-Example-789", fake.managerLogin)
        assertEquals("example-user" to "Example-Pass-123", fake.copy(apiUser = "", apiPassword = "").managerLogin)
        assertEquals(null, ServerSecrets().managerLogin)
        assertTrue(fake.hasIperfAuth)
        assertFalse(fake.copy(iperfPublicKey = "").hasIperfAuth)
    }

    /** A servers.json + history.json written by 0.1.0 must load unchanged in 0.1.1. */
    @Test fun upgradeFrom010KeepsServersAndHistory() {
        val servers = File(tmp.root, "servers.json").apply {
            writeText("""[{"id":"s1","host":"192.168.1.20","port":5201,"label":"Home NAS"},{"id":"s3","host":"iperf.example.net","port":5209,"label":""}]""")
        }
        // History format is unchanged in 0.1.1; this is exactly what 0.1.0 wrote.
        val history = File(tmp.root, "history.json").apply {
            writeText(JSONArray().apply { SampleData.history.forEach { put(Codec.record(it)) } }.toString())
        }
        val s = ServerStore(servers)
        assertEquals(listOf(Server("s1", "192.168.1.20", 5201, "Home NAS"), Server("s3", "iperf.example.net", 5209, "")), s.items.value)
        assertEquals("", s.find("s1")!!.webUrl); assertFalse(s.find("s1")!!.managerEnabled)
        assertEquals(SampleData.history.sortedBy { it.id }, HistoryStore(history).items.value.sortedBy { it.id })

        // Editing an upgraded server adds the new fields and keeps the rest.
        s.upsert(s.find("s1")!!.copy(webUrl = "https://nas.lan", managerEnabled = true))
        val reloaded = ServerStore(servers).find("s1")!!
        assertEquals("https://nas.lan", reloaded.webUrl)
        assertTrue(reloaded.managerEnabled)
        assertEquals("https://nas.lan", reloaded.managerBaseUrl)
        assertEquals(5209, ServerStore(servers).find("s3")!!.port)
    }

    @Test fun secretsNeverInServersFileOrExports() {
        val dir = tmp.root
        val servers = ServerStore(File(dir, "servers.json"))
        val saved = servers.upsert(Server("", "10.0.0.5", 5201, "Lab", webUrl = "https://lab.example"))
        SecretStore(File(dir, "secrets.json"), TestCipher()).put(saved.id, fake)
        val serversRaw = File(dir, "servers.json").readText()
        assertFalse(serversRaw.contains("Example-Pass") || serversRaw.contains("example-user") || serversRaw.contains("password"))
        val rec = SampleData.tcpUpload.copy(serverLabel = "Lab")
        val all = Export.json(listOf(rec)) + Export.csv(listOf(rec))
        listOf("example-user", "Example-Pass-123", "iperf-Example-456", "api-Example-789", "tester").forEach { assertFalse(all.contains(it)) }
    }
}
