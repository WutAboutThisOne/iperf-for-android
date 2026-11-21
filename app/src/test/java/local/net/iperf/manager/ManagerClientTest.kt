package local.net.iperf.manager

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ManagerClientTest {
    private lateinit var server: MockWebServer
    private val login = "lab-user" to "Example-Pass-123"

    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.shutdown() }

    private fun client(login: Pair<String, String>? = this.login, readMs: Int = 2000) =
        ManagerClient(server.url("/").toString(), login, requireHttps = false, connectTimeoutMs = 2000, readTimeoutMs = readMs)

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private inline fun expect(kind: ManagerException.Kind, block: () -> Unit): ManagerException {
        try { block() } catch (e: ManagerException) { assertEquals(kind, e.kind); return e }
        fail("expected $kind"); throw AssertionError()
    }

    @Test fun listsInstancesWithBasicAuth() = runBlocking<Unit> {
        server.enqueue(json("""{"instances":[
            {"port":5202,"status":"stopped","desired_state":"stopped","health":null},
            {"port":5201,"status":"running","desired_state":"running","health":{"status":"ok"}},
            {"port":5203,"status":"reserved","desired_state":"running","health":"ok","reservation":{"expires_at":"2026-11-17T22:00:00Z","minutes":15,"client":"10.0.0.9"}},
            {"port":5204,"status":"weird-new-state"}]}"""))
        val list = client().instances()
        assertEquals(listOf(5201, 5202, 5203, 5204), list.map { it.port })
        assertEquals(InstanceStatus.RUNNING, list[0].status); assertEquals("ok", list[0].health)
        assertEquals("running", list[0].desiredState)
        assertEquals(InstanceStatus.RESERVED, list[2].status)
        assertEquals(15, list[2].reservation!!.minutes); assertEquals("10.0.0.9", list[2].reservation!!.by)
        assertEquals(InstanceStatus.UNKNOWN, list[3].status); assertEquals("weird-new-state", list[3].rawStatus)
        val req = server.takeRequest()
        assertEquals("GET", req.method); assertEquals("/api/instances", req.path)
        assertEquals(ManagerClient.basic("lab-user", "Example-Pass-123"), req.getHeader("Authorization"))
        assertEquals("Basic bGFiLXVzZXI6RXhhbXBsZS1QYXNzLTEyMw==", req.getHeader("Authorization"))
    }

    @Test fun instancesAlsoAcceptBareArrayAndPortMap() = runBlocking<Unit> {
        server.enqueue(json("""[{"port":5201,"status":"degraded"}]"""))
        server.enqueue(json("""{"5205":{"status":"blocked"},"5201":{"status":"restarting"}}"""))
        assertEquals(InstanceStatus.DEGRADED, client().instances().single().status)
        val m = client().instances()
        assertEquals(listOf(5201 to InstanceStatus.RESTARTING, 5205 to InstanceStatus.BLOCKED), m.map { it.port to it.status })
    }

    @Test fun availablePorts() = runBlocking<Unit> {
        server.enqueue(json("""{"available_ports":[5204,5202,5203],"blocked_ports":[5300]}"""))
        val p = client().availablePorts()
        assertEquals(listOf(5202, 5203, 5204), p.available); assertEquals(listOf(5300), p.blocked)
        assertEquals("/api/ports/available", server.takeRequest().path)
    }

    @Test fun reserveReleaseAndInstanceActionsUseRightPathsAndBodies() = runBlocking<Unit> {
        repeat(5) { server.enqueue(json("""{"ok":true}""")) }
        val c = client()
        c.reserve(5210); c.release(5210); c.start(5211); c.stop(5211); c.restart(5212)
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/ports/5210/reserve", r.path)
        assertEquals(15, JSONObject(r.body.readUtf8()).getInt("minutes"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("/api/ports/5210/release", server.takeRequest().path)
        assertEquals("/api/instances/5211/start", server.takeRequest().path)
        assertEquals("/api/instances/5211/stop", server.takeRequest().path)
        assertEquals("/api/instances/5212/restart", server.takeRequest().path)
    }

    @Test fun logsFullThenIncremental() = runBlocking<Unit> {
        server.enqueue(json("""{"lines":["a","b"],"next":2,"total":2}"""))
        server.enqueue(json("""{"lines":[{"ts":"12:00:01","line":"c"}],"next":3,"total":3}"""))
        val c = client()
        val w1 = c.logs(5201)
        assertEquals(listOf("a", "b"), w1.lines); assertEquals(2, w1.next); assertEquals(2, w1.total)
        val w2 = c.logs(5201, since = 2)
        assertEquals(listOf("12:00:01  c"), w2.lines)
        assertEquals("/api/instances/5201/logs", server.takeRequest().path)
        assertEquals("/api/instances/5201/logs?since=2", server.takeRequest().path)
    }

    @Test fun startAllAndStopAllReportPerPortErrors() = runBlocking<Unit> {
        server.enqueue(json("""{"started":[5201,5202],"errors":{"5203":"port in use"}}"""))
        server.enqueue(json("""{"stopped":[5201],"errors":[{"port":5202,"error":"timeout"}]}"""))
        val a = client().startAll()
        assertEquals(listOf(5201, 5202), a.ok); assertEquals(mapOf(5203 to "port in use"), a.errors)
        assertEquals("2 started, 1 failed (5203: port in use)", a.describe("started"))
        val b = client().stopAll()
        assertEquals(listOf(5201), b.ok); assertEquals(mapOf(5202 to "timeout"), b.errors)
        assertEquals("/api/start-all", server.takeRequest().path)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun unauthorizedIsPlainAndNeverEchoesCredentials() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"Authentication required"}""", 401).setHeader("WWW-Authenticate", "Basic realm=\"iperf3 Manager\""))
        val e = expect(ManagerException.Kind.UNAUTHORIZED) { client().instances() }
        assertTrue(e.message.contains("rejected the login"))
        assertFalse(e.message.contains("Example-Pass-123")); assertFalse(e.message.contains("lab-user"))
    }

    @Test fun httpErrorsMapToPlainMessages() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"port 5250 already reserved"}""", 409))
        server.enqueue(json("""{"error":"not managed"}""", 404))
        server.enqueue(json("""{"error":"boom"}""", 500))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
        assertEquals("port 5250 already reserved", expect(ManagerException.Kind.CONFLICT) { client().reserve(5250) }.message)
        assertTrue(expect(ManagerException.Kind.NOT_FOUND) { client().start(9999) }.message.contains("isn't managed"))
        assertTrue(expect(ManagerException.Kind.SERVER) { client().instances() }.message.contains("HTTP 500"))
        expect(ManagerException.Kind.BAD_URL) { client().instances() } // redirect not followed (credentials stay put)
        assertEquals(4, server.requestCount)
    }

    @Test fun timeoutAndRefusedAndGarbage() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expect(ManagerException.Kind.TIMEOUT) { client(readMs = 300).instances() }
        server.enqueue(json("<html>not json</html>"))
        expect(ManagerException.Kind.BAD_RESPONSE) { client().instances() }
        val url = server.url("/").toString()
        server.shutdown()
        expect(ManagerException.Kind.NETWORK) { ManagerClient(url, login, requireHttps = false, connectTimeoutMs = 1000).instances() }
    }

    @Test fun noLoginMeansNoRequest() = runBlocking<Unit> {
        expect(ManagerException.Kind.NO_LOGIN) { client(login = null).instances() }
        assertEquals(0, server.requestCount)
    }

    @Test fun httpsRequiredByDefault() {
        expect(ManagerException.Kind.INSECURE_URL) { ManagerClient("http://iperf.example.com", login) }
        expect(ManagerException.Kind.BAD_URL) { ManagerClient("not a url", login) }
        assertEquals("https://iperf.example.com", ManagerClient("https://iperf.example.com/", login).base)
    }

    @Test fun reservationTimesShownInLocalZone() {
        val denver = java.time.ZoneId.of("America/Denver")
        assertEquals("15:00", localTime("2026-11-17T22:00:00Z", denver))
        assertEquals("15:00", localTime("1794952800", denver))
        assertEquals("soon", localTime("soon", denver))
    }

    @Test fun errorBodyParsing() {
        assertEquals("Authentication required", ManagerJson.error("""{"error":"Authentication required"}"""))
        assertNull(ManagerJson.error("Authentication required"))
    }
}
