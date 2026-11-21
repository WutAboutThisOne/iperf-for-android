package local.net.iperf.run

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import local.net.iperf.core.IperfAuth
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.TestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the real ProcessIperfEngine against a stand-in "iperf3" shell script that records what it was given. */
class ProcessEngineAuthTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun passwordGoesViaEnvAndKeyFileIsPrivateAndDeleted() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val out = File(tmp.root, "seen.txt")
        val script = File(tmp.root, "fake-iperf3").apply {
            writeText(
                """
                #!/bin/sh
                echo "ARGS:$*" > "${out.absolutePath}"
                echo "ENV:${'$'}IPERF3_PASSWORD" >> "${out.absolutePath}"
                key=""; prev=""
                for a in "$@"; do [ "${'$'}prev" = "--rsa-public-key-path" ] && key="${'$'}a"; prev="${'$'}a"; done
                echo "KEYPATH:${'$'}key" >> "${out.absolutePath}"
                echo "KEY:$(head -1 "${'$'}key")" >> "${out.absolutePath}"
                echo "PERM:$(stat -c %a "${'$'}key")" >> "${out.absolutePath}"
                echo '{"event":"error","data":"test authorization failed"}'
                exit 1
                """.trimIndent().replace("${'$'}*", "${'$'}*")
            )
            setExecutable(true)
        }
        val keyDir = File(tmp.root, "keys")
        val engine = ProcessIperfEngine(script, File(tmp.root, "tmp"), keyDir)
        val pem = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"
        val events = engine.run(TestOptions("10.0.2.2", 5202, durationSec = 1), IperfAuth("alice", "Example-Pass-123", pem)).toList()
        val seen = out.readLines().associate { it.substringBefore(':') to it.substringAfter(':') }
        assertTrue(seen["ARGS"]!!.contains("--username alice"))
        assertTrue(seen["ARGS"]!!.contains("-p 5202"))
        assertFalse(seen["ARGS"]!!.contains("Example-Pass-123"))
        assertEquals("Example-Pass-123", seen["ENV"])
        assertEquals("-----BEGIN PUBLIC KEY-----", seen["KEY"])
        assertEquals("600", seen["PERM"])
        assertTrue(seen["KEYPATH"]!!.startsWith(keyDir.absolutePath))
        assertFalse("key file must be deleted after the run", File(seen["KEYPATH"]!!).exists())
        assertTrue(events.any { it is IperfEvent.Error && it.message.contains("authorization failed") })
    }

    @Test fun noAuthMeansNoPasswordEnv() = runBlocking {
        val out = File(tmp.root, "seen2.txt")
        val script = File(tmp.root, "fake2").apply {
            writeText("#!/bin/sh\necho \"ENV:${'$'}{IPERF3_PASSWORD-unset}\" > \"${out.absolutePath}\"\necho \"ARGS:${'$'}*\" >> \"${out.absolutePath}\"\nexit 1\n")
            setExecutable(true)
        }
        ProcessIperfEngine(script, File(tmp.root, "tmp")).run(TestOptions("h")).toList()
        val seen = out.readLines()
        assertEquals("ENV:unset", seen[0])
        assertFalse(seen[1].contains("--username"))
    }
}
