package local.net.iperf.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A failed manager call, already worded for people. */
class ManagerException(val kind: Kind, override val message: String) : Exception(message) {
    enum class Kind { INSECURE_URL, BAD_URL, NO_LOGIN, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, SERVER, TIMEOUT, NETWORK, TLS, BAD_RESPONSE }
}

interface ManagerApi {
    suspend fun instances(): List<ManagedInstance>
    suspend fun availablePorts(): PortAvailability
    suspend fun reserve(port: Int, minutes: Int = ManagerClient.DEFAULT_RESERVE_MINUTES)
    suspend fun release(port: Int)
    suspend fun start(port: Int)
    suspend fun stop(port: Int)
    suspend fun restart(port: Int)
    suspend fun logs(port: Int, since: Int? = null): LogWindow
    suspend fun startAll(): BulkResult
    suspend fun stopAll(): BulkResult
}

/**
 * HTTP client for an "iperf3 Manager" (Basic auth, realm "iperf3 Manager"). Uses HttpURLConnection, so no extra
 * dependency. HTTPS is required unless [requireHttps] is false (tests). Credentials only ever go into the
 * Authorization header; nothing here logs.
 */
class ManagerClient(
    baseUrl: String,
    private val login: Pair<String, String>?,
    private val requireHttps: Boolean = true,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 10000,
) : ManagerApi {
    val base: String = validateBase(baseUrl, requireHttps)
    private val host: String = runCatching { URI(base).host }.getOrNull() ?: base

    override suspend fun instances() = parse { ManagerJson.instances(call("GET", "/api/instances")) }
    override suspend fun availablePorts() = parse { ManagerJson.ports(call("GET", "/api/ports/available")) }
    override suspend fun reserve(port: Int, minutes: Int) { call("POST", "/api/ports/$port/reserve", JSONObject().put("minutes", minutes).toString()) }
    override suspend fun release(port: Int) { call("POST", "/api/ports/$port/release") }
    override suspend fun start(port: Int) { call("POST", "/api/instances/$port/start") }
    override suspend fun stop(port: Int) { call("POST", "/api/instances/$port/stop") }
    override suspend fun restart(port: Int) { call("POST", "/api/instances/$port/restart") }
    override suspend fun logs(port: Int, since: Int?) =
        parse { ManagerJson.logs(call("GET", "/api/instances/$port/logs" + (since?.let { "?since=$it" } ?: ""))) }
    override suspend fun startAll() = parse { ManagerJson.bulk(call("POST", "/api/start-all"), "started") }
    override suspend fun stopAll() = parse { ManagerJson.bulk(call("POST", "/api/stop-all"), "stopped") }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: org.json.JSONException) {
        throw ManagerException(ManagerException.Kind.BAD_RESPONSE, "$host answered, but not like an iperf3 Manager.")
    }

    private suspend fun call(method: String, path: String, json: String? = null): String {
        val creds = login ?: throw ManagerException(ManagerException.Kind.NO_LOGIN,
            "No login saved for the manager. Add the webpage login (or a separate API login) under Edit server.")
        return withContext(Dispatchers.IO) { exchange(method, path, json, creds) }
    }

    private fun exchange(method: String, path: String, json: String?, creds: Pair<String, String>): String {
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ManagerException(ManagerException.Kind.BAD_URL, "The manager URL isn't valid.")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false // never replay credentials to another host
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", basic(creds.first, creds.second))
            if (method == "POST") {
                conn.doOutput = true
                val bytes = (json ?: "{}").toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code in 200..299) return body
            throw httpError(code, ManagerJson.error(body), path)
        } catch (e: ManagerException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ManagerException(ManagerException.Kind.TIMEOUT, "$host didn't answer in time. The manager may be down or slow — try again.")
        } catch (e: UnknownHostException) {
            throw ManagerException(ManagerException.Kind.NETWORK, "Can't find $host. Check the manager URL and that you're online.")
        } catch (e: ConnectException) {
            throw ManagerException(ManagerException.Kind.NETWORK, "Couldn't connect to $host. The manager may be down, or your network blocks it.")
        } catch (e: NoRouteToHostException) {
            throw ManagerException(ManagerException.Kind.NETWORK, "No network route to $host.")
        } catch (e: SSLException) {
            throw ManagerException(ManagerException.Kind.TLS, "Secure connection to $host failed (certificate or TLS problem).")
        } catch (e: org.json.JSONException) {
            throw ManagerException(ManagerException.Kind.BAD_RESPONSE, "$host answered, but not like an iperf3 Manager.")
        } catch (e: IOException) {
            throw ManagerException(ManagerException.Kind.NETWORK, "Network error talking to $host. Check your connection and try again.")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpError(code: Int, serverMsg: String?, path: String): ManagerException {
        val detail = serverMsg?.let { " ($it)" } ?: ""
        return when (code) {
            401 -> ManagerException(ManagerException.Kind.UNAUTHORIZED,
                "The manager rejected the login. Check the webpage/API username and password under Edit server.")
            403 -> ManagerException(ManagerException.Kind.FORBIDDEN, "This login isn't allowed to do that$detail.")
            404 -> ManagerException(ManagerException.Kind.NOT_FOUND,
                if (path.startsWith("/api/instances/") || path.startsWith("/api/ports/")) "That port isn't managed by this server$detail."
                else "No iperf3 Manager API at this URL$detail.")
            409 -> ManagerException(ManagerException.Kind.CONFLICT, serverMsg ?: "The manager refused: that port is busy or already reserved.")
            in 300..399 -> ManagerException(ManagerException.Kind.BAD_URL, "The manager URL redirects elsewhere. Use the final https:// address.")
            in 500..599 -> ManagerException(ManagerException.Kind.SERVER, "The manager hit an error (HTTP $code)$detail.")
            else -> ManagerException(ManagerException.Kind.SERVER, "The manager answered HTTP $code$detail.")
        }
    }

    companion object {
        const val DEFAULT_RESERVE_MINUTES = 15

        fun basic(user: String, password: String): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

        /** Base URL without trailing slash; https required unless [requireHttps] is false. */
        fun validateBase(raw: String, requireHttps: Boolean = true): String {
            val t = raw.trim().trimEnd('/')
            val u = runCatching { URI(t) }.getOrNull()
            if (u?.host.isNullOrBlank()) throw ManagerException(ManagerException.Kind.BAD_URL, "The manager URL isn't valid.")
            val scheme = u!!.scheme?.lowercase()
            if (scheme == "http" && requireHttps) throw ManagerException(ManagerException.Kind.INSECURE_URL,
                "The manager URL must use https:// so your login isn't sent in the clear.")
            if (scheme != "https" && scheme != "http") throw ManagerException(ManagerException.Kind.BAD_URL, "The manager URL must start with https://")
            return t
        }
    }
}
