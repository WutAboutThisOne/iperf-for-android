package local.net.iperf

import java.io.File

object Fixtures {
    private val dir = File(System.getProperty("fixtures.dir") ?: "src/test/resources/fixtures")
    fun text(name: String): String = File(dir, name).readText()
}
