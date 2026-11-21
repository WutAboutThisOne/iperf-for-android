package local.net.iperf.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.net.iperf.manager.BulkResult
import local.net.iperf.manager.InstanceAction
import local.net.iperf.manager.ManagerControllerTest
import local.net.iperf.manager.ManagerController
import local.net.iperf.manager.ManagerException
import local.net.iperf.manager.ActivityLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val server = Server("n1", "iperf.example.net", 5201, "Lab server", "https://iperf.example.net", managerEnabled = true)
    private val secrets = ServerSecrets(webUser = "lab-admin", webPassword = "Hunter2-Example!", iperfUser = "tester",
        iperfPassword = "Iperf-Secret-42", iperfPublicKey = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n")

    private fun entry(t: Long, serverId: String = "n1", action: LogAction = LogAction.START, ok: Boolean = true, detail: String? = "Started") =
        ActivityEntry(id = "$serverId-$t", timeMs = t, serverId = serverId, serverTitle = serverId, ports = listOf(5201), action = action, ok = ok, detail = detail)

    @Test fun persistsAcrossRestartsNewestFirst() {
        val f = tmp.newFile("activity-log.json").apply { delete() }
        ActivityLogStore(f).apply {
            add(entry(1_000)); add(entry(3_000, action = LogAction.STOP)); add(entry(2_000, "other"))
        }
        val reopened = ActivityLogStore(f) // e.g. after the app was killed
        assertEquals(listOf(3_000L, 2_000L, 1_000L), reopened.items.value.map { it.timeMs })
        assertEquals(listOf(3_000L, 1_000L), reopened.forServer("n1").map { it.timeMs })
        assertEquals(LogAction.STOP, reopened.items.value.first().action)
        reopened.clear("n1")
        assertEquals(listOf("other"), ActivityLogStore(f).items.value.map { it.serverId })
    }

    @Test fun cappedPerServer() {
        val store = ActivityLogStore(tmp.newFile().apply { delete() }, maxPerServer = 50)
        repeat(120) { store.add(entry(it.toLong())) }
        repeat(10) { store.add(entry(it.toLong(), "other")) }
        assertEquals(50, store.forServer("n1").size)
        assertEquals(119L, store.forServer("n1").first().timeMs)
        assertEquals(70L, store.forServer("n1").last().timeMs)
        assertEquals(10, store.forServer("other").size)
    }

    @Test fun entriesAndExportsNeverContainCredentials() {
        val leaky = "Couldn't connect to https://lab-admin:Hunter2-Example!@iperf.example.net (password=Hunter2-Example!, Authorization: " +
            ManagerClientBasic + ") iperf user tester pw Iperf-Secret-42"
        val e = ActivityEntries.of(server, secrets, LogAction.START, listOf(5280), false, leaky, startAndTest = true, now = 1_794_956_400_000)
        val store = ActivityLogStore(tmp.newFile().apply { delete() })
        store.add(e)
        store.add(ActivityEntries.of(server, secrets, LogAction.RESERVE, listOf(5280), true, "Reserved for 15 min", true, 1_794_956_460_000))
        val csv = ActivityLogStore.csv(store.forServer("n1"), zone)
        val json = ActivityLogStore.json(store.forServer("n1"), zone, exportedAt = 0)
        for (out in listOf(e.detail!!, csv, json)) {
            for (secret in listOf("Hunter2-Example!", "Iperf-Secret-42", "lab-admin", "tester", ManagerClientBasic.removePrefix("Basic "))) {
                assertFalse("leaked '$secret' in: $out", out.contains(secret))
            }
        }
        assertTrue(e.detail!!.contains("[redacted]"))
        // CSV: header + time with the phone's offset, server, ports, action, result, detail.
        val lines = csv.trim().lines()
        assertEquals("time,server,ports,action,result,detail", lines[0])
        assertTrue(lines[1], lines[1].startsWith("2026-11-17T15:01:00-08:00,Lab server,5280,Start & test · Reserve,OK,Reserved for 15 min"))
        assertTrue(lines[2].contains(",Start & test · Start,Failed,"))
        val parsed = JSONObject(json)
        assertEquals("server_activity_log", parsed.getString("kind"))
        assertEquals(2, parsed.getJSONArray("entries").length())
        assertEquals(false, parsed.getJSONArray("entries").getJSONObject(1).getBoolean("ok"))
    }

    @Test fun managerControllerLogsActionsAndBulk() = runTest(UnconfinedTestDispatcher()) {
        val api = ManagerControllerTest.FakeApi()
        val logged = mutableListOf<Triple<LogAction, List<Int>, Boolean>>()
        val details = mutableListOf<String?>()
        val c = ManagerController(api, backgroundScope, "Lab", "https://iperf.example.net",
            activity = ActivityLogger { a, p, ok, d, sat -> logged += Triple(a, p, ok); details += d; assertFalse(sat) })
        c.act(5202, InstanceAction.START).join()
        c.act(5201, InstanceAction.RESTART).join()
        c.act(5203, InstanceAction.RESERVE).join()
        c.act(5203, InstanceAction.RELEASE).join()
        c.act(5201, InstanceAction.STOP).join()
        c.startAll().join()
        c.stopAll().join()
        api.failWith = ManagerException(ManagerException.Kind.CONFLICT, "Port 5202 is busy.")
        c.act(5202, InstanceAction.START).join()
        assertEquals(listOf(
            Triple(LogAction.START, listOf(5202), true), Triple(LogAction.RESTART, listOf(5201), true),
            Triple(LogAction.RESERVE, listOf(5203), true), Triple(LogAction.RELEASE, listOf(5203), true),
            Triple(LogAction.STOP, listOf(5201), true),
            Triple(LogAction.START_ALL, listOf(5201, 5202, 5203), false), // 5203 failed: "busy"
            Triple(LogAction.STOP_ALL, listOf(5201), true),
            Triple(LogAction.START, listOf(5202), false),
        ), logged)
        assertEquals("Port 5202 is busy.", details.last())
        assertEquals("2 started, 1 failed (5203: busy)", details[5])
    }

    companion object {
        val ManagerClientBasic = local.net.iperf.manager.ManagerClient.basic("lab-admin", "Hunter2-Example!")
    }
}
