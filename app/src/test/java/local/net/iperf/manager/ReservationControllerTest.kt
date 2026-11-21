package local.net.iperf.manager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.net.iperf.data.InMemoryReservations
import local.net.iperf.data.LogAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationControllerTest {
    data class Logged(val action: LogAction, val ports: List<Int>, val ok: Boolean, val detail: String?)

    private val now = 1_000_000_000_000L
    private val tracker = InMemoryReservations()
    private val log = mutableListOf<Logged>()
    private var unsupportedCalls = 0

    private fun TestScope.controller(api: ManagerApi, extendSupported: Boolean = true) = ManagerController(
        api, backgroundScope, "Lab", "https://iperf.example.net", activity = { a, p, ok, d, _ -> log += Logged(a, p, ok, d) },
        serverId = "srv", tracker = tracker, extendSupported = extendSupported, onExtendUnsupported = { unsupportedCalls++ },
        clock = { now },
    )

    @Test fun reserveAndReleaseAreTracked() = runTest(UnconfinedTestDispatcher()) {
        val api = ManagerControllerTest.FakeApi(); val c = controller(api)
        c.act(5203, InstanceAction.RESERVE).join()
        assertEquals(mapOf(5203 to now + 15 * 60_000), c.state.value.mine)
        c.act(5203, InstanceAction.RELEASE).join()
        assertTrue(c.state.value.mine.isEmpty())
        assertTrue(tracker.items.value.isEmpty())
    }

    @Test fun extendReservesAgainAndLogs() = runTest(UnconfinedTestDispatcher()) {
        val api = ManagerControllerTest.FakeApi(); val c = controller(api)
        tracker.put("srv", 5203, 15, now - 10 * 60_000) // 5 min left
        c.syncMine()
        api.calls.clear()
        c.extend(5203).join()
        assertEquals("reserve 5203 15", api.calls.first())
        assertEquals(now + 15 * 60_000, c.state.value.mine[5203])
        assertEquals(LogAction.EXTEND, log.last().action)
        assertTrue(log.last().ok)
        assertTrue(log.last().detail!!.startsWith("Extended by 15 min"))
        assertTrue(c.state.value.extendSupported)
    }

    @Test fun extendRefusedHidesExtend() = runTest(UnconfinedTestDispatcher()) {
        val api = object : ManagerApi by ManagerControllerTest.FakeApi() {
            override suspend fun reserve(port: Int, minutes: Int) { throw ManagerException(ManagerException.Kind.CONFLICT, "already reserved") }
        }
        val c = controller(api)
        tracker.put("srv", 5203, 15, now)
        c.extend(5203).join()
        assertFalse(c.state.value.extendSupported)
        assertEquals(1, unsupportedCalls)
        assertTrue(c.state.value.message!!.contains("Extend is now hidden"))
        assertEquals(LogAction.EXTEND, log.last().action)
        assertFalse(log.last().ok)
        // Still reserved by us: the original reservation is untouched.
        assertEquals(setOf(5203), c.state.value.mine.keys)
    }

    @Test fun releaseAllMineReleasesOnlyOurPorts() = runTest(UnconfinedTestDispatcher()) {
        val fake = ManagerControllerTest.FakeApi()
        val api = object : ManagerApi by fake {
            override suspend fun release(port: Int) {
                fake.release(port)
                if (port == 5207) throw ManagerException(ManagerException.Kind.NOT_FOUND, "That port isn't managed by this server.")
                if (port == 5208) throw ManagerException(ManagerException.Kind.SERVER, "The manager hit an error (HTTP 500).")
            }
        }
        val c = controller(api)
        listOf(5205, 5206, 5207, 5208).forEach { tracker.put("srv", it, 15, now) }
        tracker.put("other", 5209, 15, now)
        c.syncMine()
        assertEquals(setOf(5205, 5206, 5207, 5208), c.state.value.mine.keys)
        c.releaseAllMine().join()
        assertEquals(listOf("release 5205", "release 5206", "release 5207", "release 5208"), fake.calls.filter { it.startsWith("release") })
        // 5207 was already gone on the manager: forgotten; 5208 failed: still ours.
        assertEquals(setOf(5208), c.state.value.mine.keys)
        assertEquals(1, tracker.forServer("other", now).size)
        val entry = log.last()
        assertEquals(LogAction.RELEASE_ALL, entry.action)
        assertEquals(listOf(5205, 5206, 5207, 5208), entry.ports)
        assertFalse(entry.ok)
        assertTrue(entry.detail!!.startsWith("Released 5205, 5206, 5207; failed 5208"))
        assertFalse(c.state.value.bulkBusy)
    }

    @Test fun releaseAllMineWithNothingReserved() = runTest(UnconfinedTestDispatcher()) {
        val api = ManagerControllerTest.FakeApi(); val c = controller(api)
        c.releaseAllMine().join()
        assertEquals("No ports reserved from this phone", c.state.value.message)
        assertTrue(api.calls.none { it.startsWith("release") })
    }
}
