package local.net.iperf.manager

import local.net.iperf.ui.manager.startAndTestLabel


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import local.net.iperf.data.LogAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start & test against a mock manager (no network). */
@OptIn(ExperimentalCoroutinesApi::class)
class StartAndTestTest {
    data class Logged(val action: LogAction, val ports: List<Int>, val ok: Boolean, val detail: String?, val sat: Boolean)

    private class Recorder : ActivityLogger {
        val entries = mutableListOf<Logged>()
        override fun log(action: LogAction, ports: List<Int>, ok: Boolean, detail: String?, startAndTest: Boolean) {
            entries += Logged(action, ports, ok, detail, startAndTest)
        }
    }

    @Test fun successReservesStartsWaitsUntilReachable() = runTest {
        val api = ManagerControllerTest.FakeApi().apply { instances = listOf(ManagedInstance(5202, InstanceStatus.RUNNING, "running")) }
        val log = Recorder()
        var probes = 0
        val flow = StartAndTest(api, log, "iperf.example.net", reachable = { h, p ->
            assertEquals("iperf.example.net", h); assertEquals(5202, p); ++probes >= 3 // answers on the 3rd try
        }, now = { currentTime })
        val steps = mutableListOf<String>()
        val r = flow.prepare(5202, alreadyRunning = false) { steps += it }
        assertEquals(StartTestResult.Ready(5202, startedByUs = true), r)
        assertEquals(listOf("start 5202", "reserve 5202 15", "instances", "instances", "instances"), api.calls)
        assertEquals(listOf(LogAction.START, LogAction.RESERVE, LogAction.WAIT_REACHABLE), log.entries.map { it.action })
        assertTrue(log.entries.all { it.ok && it.sat && it.ports == listOf(5202) })
        assertEquals(3, steps.size)
    }

    @Test fun alreadyRunningInstanceIsNotStartedAgain() = runTest {
        val api = ManagerControllerTest.FakeApi()
        val r = StartAndTest(api, Recorder(), "h", reachable = { _, _ -> true }, now = { currentTime }).prepare(5201, alreadyRunning = true)
        assertEquals(StartTestResult.Ready(5201, startedByUs = false), r)
        assertTrue(api.calls.none { it.startsWith("start") })
    }

    @Test fun startFailureLeavesThePortUntouched() = runTest {
        val api = object : ManagerApi by ManagerControllerTest.FakeApi() {
            val calls = mutableListOf<String>()
            override suspend fun reserve(port: Int, minutes: Int) { calls += "reserve $port" }
            override suspend fun start(port: Int) { calls += "start $port"; throw ManagerException(ManagerException.Kind.SERVER, "The manager hit an error (HTTP 500) (port in use).") }
            override suspend fun release(port: Int) { calls += "release $port" }
            override suspend fun stop(port: Int) { calls += "stop $port" }
        }
        val log = Recorder()
        var probed = false
        val r = StartAndTest(api, log, "h", reachable = { _, _ -> probed = true; true }, now = { currentTime }).prepare(5280, alreadyRunning = false)
        r as StartTestResult.Failed
        assertEquals("Couldn't start port 5280: The manager hit an error (HTTP 500) (port in use).", r.message)
        assertEquals(listOf("start 5280"), api.calls) // never reserved or released: start never succeeded
        assertEquals(false, probed)
        assertEquals(listOf(LogAction.START to false), log.entries.map { it.action to it.ok })
        assertEquals("The manager hit an error (HTTP 500) (port in use).", log.entries[0].detail)
    }

    @Test fun reserveFailureAfterStartStopsTheInstance() = runTest {
        val api = object : ManagerApi by ManagerControllerTest.FakeApi() {
            val calls = mutableListOf<String>()
            override suspend fun start(port: Int) { calls += "start $port" }
            override suspend fun reserve(port: Int, minutes: Int) {
                calls += "reserve $port"
                throw ManagerException(ManagerException.Kind.CONFLICT, "Port $port is already reserved.")
            }
            override suspend fun release(port: Int) { calls += "release $port" }
            override suspend fun stop(port: Int) { calls += "stop $port" }
        }
        val log = Recorder()
        val r = StartAndTest(api, log, "h", reachable = { _, _ -> true }, now = { currentTime }).prepare(5280, alreadyRunning = false)
        r as StartTestResult.Failed
        assertEquals("Couldn't reserve port 5280: Port 5280 is already reserved. The instance was stopped again.", r.message)
        assertEquals(listOf("start 5280", "reserve 5280", "stop 5280"), api.calls) // no release: reserve never succeeded
        assertEquals(listOf(LogAction.START to true, LogAction.RESERVE to false, LogAction.STOP to true), log.entries.map { it.action to it.ok })
    }

    @Test fun portNeverReachableTimesOutAndRollsBack() = runTest {
        val api = ManagerControllerTest.FakeApi().apply { instances = listOf(ManagedInstance(5202, InstanceStatus.RUNNING, "running")) }
        val log = Recorder()
        var probes = 0
        val r = StartAndTest(api, log, "h", reachable = { _, _ -> probes++; false }, timeoutMs = 20_000, pollMs = 1_000, now = { currentTime })
            .prepare(5202, alreadyRunning = false)
        r as StartTestResult.Failed
        assertTrue(r.message, r.message.contains("didn't accept connections at h:5202 within 20 s"))
        assertTrue(r.message.contains("stopped again"))
        assertTrue("about 20 s of virtual time, was $currentTime", currentTime in 20_000..21_000)
        assertTrue(probes in 20..22)
        assertEquals(listOf("stop 5202", "release 5202"), api.calls.filter { it.startsWith("release") || it.startsWith("stop") })
        assertEquals(listOf(LogAction.START, LogAction.RESERVE, LogAction.WAIT_REACHABLE, LogAction.STOP, LogAction.RELEASE), log.entries.map { it.action })
        assertEquals(false, log.entries[2].ok)
    }

    @Test fun reserveConflictOnAlreadyRunningChangesNothing() = runTest {
        val api = ManagerControllerTest.FakeApi().apply { failWith = ManagerException(ManagerException.Kind.CONFLICT, "Port 5202 is already reserved.") }
        val log = Recorder()
        val r = StartAndTest(api, log, "h", reachable = { _, _ -> true }, now = { currentTime }).prepare(5202, alreadyRunning = true)
        r as StartTestResult.Failed
        assertEquals("Couldn't reserve port 5202: Port 5202 is already reserved.", r.message)
        assertEquals(listOf("reserve 5202 15"), api.calls)
        assertEquals(1, log.entries.size)
    }

    @Test fun startAndTestLabels() {
        assertEquals("Start & test", startAndTestLabel(ManagedInstance(1, InstanceStatus.STOPPED, "stopped")))
        assertEquals("Reserve & test", startAndTestLabel(ManagedInstance(1, InstanceStatus.RUNNING, "running")))
        assertEquals(null, startAndTestLabel(ManagedInstance(1, InstanceStatus.RUNNING, "running", reservation = Reservation(by = "x"))))
        assertEquals(null, startAndTestLabel(ManagedInstance(1, InstanceStatus.BLOCKED, "blocked")))
    }

    @Test fun tcpSuccessCountsEvenIfManagerStillSaysStopped() = runTest {
        // After start(), the manager can keep reporting STOPPED until a later health update.
        val api = ManagerControllerTest.FakeApi().apply { instances = listOf(ManagedInstance(5203, InstanceStatus.STOPPED, "stopped")) }
        var probes = 0
        val r = StartAndTest(api, Recorder(), "192.0.2.10", reachable = { h, p ->
            assertEquals("192.0.2.10", h); assertEquals(5203, p); probes++; true
        }, now = { currentTime }).prepare(5203, alreadyRunning = false)
        assertEquals(StartTestResult.Ready(5203, startedByUs = true), r)
        assertEquals(1, probes)
        assertTrue(api.calls.none { it.startsWith("release") || it.startsWith("stop") })
    }

    @Test fun reservedStatusAllowsTcpProbeToSucceed() = runTest {
        val api = ManagerControllerTest.FakeApi().apply {
            instances = listOf(ManagedInstance(5203, InstanceStatus.RESERVED, "reserved", "running", "ok"))
        }
        val r = StartAndTest(api, Recorder(), "192.0.2.10", reachable = { h, p ->
            assertEquals("192.0.2.10", h); assertEquals(5203, p); true
        }, now = { currentTime }).prepare(5203, alreadyRunning = false)
        assertEquals(StartTestResult.Ready(5203, startedByUs = true), r)
    }

    /**
     * the lab manager (observed on a real Start & test of a free stopped port): POST start is
     * refused with 409 while the port is reserved, even by the same login that just reserved it.
     * Start first, then reserve, so the reservation cannot block our own start.
     */
    @Test fun startThenReserveWhenManagerRejectsStartOnReservedPorts() = runTest {
        val api = RejectsStartIfReservedApi()
        val log = Recorder()
        val r = StartAndTest(api, log, "h", reachable = { _, _ -> true }, now = { currentTime })
            .prepare(5280, alreadyRunning = false)
        assertEquals(StartTestResult.Ready(5280, startedByUs = true), r)
        assertEquals(listOf("start 5280", "reserve 5280 15", "instances"), api.calls)
        assertEquals(listOf(LogAction.START, LogAction.RESERVE, LogAction.WAIT_REACHABLE), log.entries.map { it.action })
        assertTrue(log.entries.all { it.ok && it.sat && it.ports == listOf(5280) })
    }

    /** Mimics a manager that rejects start while the port is reserved (including by us). */
    private class RejectsStartIfReservedApi : ManagerApi by ManagerControllerTest.FakeApi() {
        val calls = mutableListOf<String>()
        private val reserved = mutableSetOf<Int>()
        override suspend fun reserve(port: Int, minutes: Int) { calls += "reserve $port $minutes"; reserved += port }
        override suspend fun release(port: Int) { calls += "release $port"; reserved -= port }
        override suspend fun start(port: Int) {
            calls += "start $port"
            if (port in reserved) throw ManagerException(
                ManagerException.Kind.CONFLICT,
                "Port $port is reserved until 2026-11-21T05:36:07.152824Z",
            )
        }
        override suspend fun stop(port: Int) { calls += "stop $port" }
        override suspend fun instances(): List<ManagedInstance> {
            calls += "instances"
            return listOf(ManagedInstance(5280, InstanceStatus.RUNNING, "running"))
        }
    }
}
