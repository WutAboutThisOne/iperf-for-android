package local.net.iperf.core

import local.net.iperf.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {
    @Test fun parsesHeadersBulletsAndContinuations() {
        val log = Changelog.parse(
            """
            # comment line
            ## 1.2.0 — 2026-12-01
            - New thing
              that wraps
            - Second
            Some prose that is ignored.
            ## v1.1.0
            - Old
            """.trimIndent()
        )
        assertEquals(listOf("1.2.0", "1.1.0"), log.map { it.version })
        assertEquals("2026-12-01", log[0].date)
        assertEquals(listOf("New thing that wraps", "Second"), log[0].items)
        assertNull(log[1].date)
    }

    @Test fun bundledChangelogStartsWithThisVersionAndKeepsHistory() {
        val f = listOf(File("src/main/assets/CHANGELOG.md"), File("app/src/main/assets/CHANGELOG.md")).first { it.exists() }
        val log = Changelog.parse(f.readText())
        assertEquals(BuildConfig.VERSION_NAME, log.first().version)
        assertEquals(listOf("0.2.0", "0.1.4", "0.1.3", "0.1.2", "0.1.1", "0.1.0"), log.map { it.version })
        assertTrue(log.all { it.items.isNotEmpty() })
        assertTrue(log.last().items.any { "iperf3 3.22" in it })
        assertTrue(log.first { it.version == "0.1.1" }.items.any { "Manager" in it })
        assertTrue(log.first { it.version == "0.1.2" }.items.any { "Endless" in it })
        assertTrue(log.first { it.version == "0.1.3" }.items.any { "Start & test" in it })
        assertTrue(log.first { it.version == "0.1.3" }.items.any { "History" in it })
        assertTrue(log.first().items.any { "Renamed" in it })
        assertTrue(log.first().items.any { "Apache License 2.0" in it })
        val v014 = log.first { it.version == "0.1.4" }
        assertTrue(v014.items.any { "Fold" in it })
        assertTrue(v014.items.any { "Release all mine" in it })
        assertTrue(v014.items.any { "Port search" in it })
    }
}
