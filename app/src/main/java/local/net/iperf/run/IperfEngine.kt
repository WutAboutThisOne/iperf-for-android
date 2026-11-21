package local.net.iperf.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import local.net.iperf.core.IperfAuth
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.IperfJson
import local.net.iperf.core.TestOptions
import java.io.File
import java.util.concurrent.TimeUnit

interface IperfEngine {
    /** Runs one test; emits parsed events until iperf3 exits. [auth] adds the iperf3 server login. */
    fun run(options: TestOptions, auth: IperfAuth? = null): Flow<IperfEvent>
    /** Asks the running test to stop (iperf3 then prints a partial summary). */
    fun stop()
}

/**
 * Runs the bundled iperf3 executable. It's packaged as `libiperf3.so` so the installer extracts it to
 * `nativeLibraryDir`, which is the one app-owned location Android 10+ still allows exec() from.
 */
class ProcessIperfEngine(
    private val binary: File,
    private val tmpDir: File,
    /** App-private, no-backup dir for the short-lived public key file. */
    private val keyDir: File = tmpDir,
) : IperfEngine {
    @Volatile private var process: Process? = null

    override fun run(options: TestOptions, auth: IperfAuth?): Flow<IperfEvent> = flow {
        if (!binary.exists()) {
            emit(IperfEvent.Error("iperf3 binary missing at ${binary.path} (unsupported CPU ABI?)"))
            return@flow
        }
        tmpDir.mkdirs()
        val keyFile = auth?.let { writeKey(it.publicKeyPem) }
        val pb = ProcessBuilder(buildCommand(binary.absolutePath, options, auth, keyFile?.absolutePath))
        // iperf3 creates its send buffer with mkstemp in $TMPDIR (default /data/local/tmp isn't writable).
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        // The password goes only through the environment (iperf3 reads IPERF3_PASSWORD), never argv.
        pb.environment().remove(IperfAuth.PASSWORD_ENV)
        if (auth != null) pb.environment()[IperfAuth.PASSWORD_ENV] = auth.password
        val p = try { pb.start() } catch (e: Exception) { keyFile?.delete(); throw e }
        process = p
        val stderr = StringBuilder()
        val errThread = Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        var sawTerminal = false
        try {
            p.inputStream.bufferedReader().use { r ->
                while (currentCoroutineContext().isActive) {
                    val line = r.readLine() ?: break
                    val ev = IperfJson.parseLine(line) ?: continue
                    if (ev is IperfEvent.Error || ev is IperfEvent.Finished) sawTerminal = true
                    emit(ev)
                }
            }
            val code = if (p.waitFor(3, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
            errThread.join(500)
            if (!sawTerminal && code != 0) {
                val msg = synchronized(stderr) { stderr.toString().trim() }
                emit(IperfEvent.Error(msg.lineSequence().lastOrNull { it.isNotBlank() }?.removePrefix("iperf3: ")
                    ?: "iperf3 exited with code $code"))
            }
        } finally {
            if (p.isAlive) p.destroyForcibly()
            process = null
            keyFile?.delete()
        }
    }.flowOn(Dispatchers.IO)

    private fun writeKey(pem: String): File {
        keyDir.mkdirs()
        val f = File.createTempFile("server-", ".pem", keyDir)
        f.setReadable(false, false); f.setReadable(true, true); f.setWritable(true, true)
        f.writeText(pem)
        return f
    }

    override fun stop() {
        val p = process ?: return
        p.destroy() // SIGTERM: iperf3 sends its partial results then exits
        Thread {
            if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroyForcibly()
        }.apply { isDaemon = true; start() }
    }
}

/** Full iperf3 argv. Exposed for tests: the password must never appear here. */
fun buildCommand(binary: String, options: TestOptions, auth: IperfAuth?, keyPath: String?): List<String> =
    listOf(binary) + options.toArgs() + (if (auth != null && keyPath != null) auth.args(keyPath) else emptyList())

/** Delegates to [delegate]; tests swap in a fake engine on the process-wide container. */
class SwitchableEngine(@Volatile var delegate: IperfEngine) : IperfEngine {
    override fun run(options: TestOptions, auth: IperfAuth?): Flow<IperfEvent> = delegate.run(options, auth)
    override fun stop() = delegate.stop()
}
