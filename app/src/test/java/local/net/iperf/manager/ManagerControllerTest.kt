package local.net.iperf.manager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerControllerTest {
    class FakeApi : ManagerApi {
        val calls = mutableListOf<String>()
        var instances = listOf(ManagedInstance(5201, InstanceStatus.RUNNING, "running"), ManagedInstance(5202, InstanceStatus.STOPPED, "stopped"))
        var failWith: ManagerException? = null
        var logResponses = ArrayDeque<LogWindow>()
        private fun maybeFail() { failWith?.let { throw it } }
        override suspend fun instances(): List<ManagedInstance> { calls += "instances"; maybeFail(); return instances }
        override suspend fun availablePorts(): PortAvailability { calls += "ports"; return PortAvailability(listOf(5203, 5204), listOf(5300)) }
        override suspend fun reserve(port: Int, minutes: Int) { calls += "reserve $port $minutes"; maybeFail() }
        override suspend fun release(port: Int) { calls += "release $port" }
        override suspend fun start(port: Int) { calls += "start $port"; maybeFail() }
        override suspend fun stop(port: Int) { calls += "stop $port" }
        override suspend fun restart(port: Int) { calls += "restart $port" }
        override suspend fun logs(port: Int, since: Int?): LogWindow { calls += "logs $port ${since ?: "-"}"; return logResponses.removeFirst() }
        override suspend fun startAll(): BulkResult { calls += "startAll"; return BulkResult(listOf(5201, 5202), mapOf(5203 to "busy")) }
        override suspend fun stopAll(): BulkResult { calls += "stopAll"; return BulkResult(listOf(5201), emptyMap()) }
    }

    private fun TestScope.controller(api: FakeApi) = ManagerController(api, backgroundScope, "Lab", "https://iperf.example.com", logPollMs = 1000)

    @Test fun refreshLoadsInstancesAndPorts() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(); val c = controller(api)
        c.refresh().join()
        assertEquals(listOf(5201, 5202), c.state.value.instances.map { it.port })
        assertEquals(listOf(5203, 5204), c.state.value.ports!!.available)
        assertFalse(c.state.value.loading); assertNull(c.state.value.error)
    }

    @Test fun refreshErrorIsShownPlainly() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi().apply { failWith = ManagerException(ManagerException.Kind.UNAUTHORIZED, "The manager rejected the login.") }
        val c = controller(api)
        c.refresh().join()
        assertEquals("The manager rejected the login.", c.state.value.error)
    }

    @Test fun actionsCallApiThenRefresh() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(); val c = controller(api)
        c.act(5202, InstanceAction.START).join()
        assertEquals(listOf("start 5202", "instances", "ports"), api.calls)
        assertEquals("Port 5202 started", c.state.value.message)
        assertTrue(c.state.value.busy.isEmpty())
        api.calls.clear()
        c.act(5203, InstanceAction.RESERVE).join()
        assertEquals("reserve 5203 15", api.calls.first())
        api.failWith = ManagerException(ManagerException.Kind.CONFLICT, "already reserved")
        c.act(5203, InstanceAction.RESERVE).join()
        assertEquals("Reserve 5203 failed: already reserved", c.state.value.message)
    }

    @Test fun bulkReportsCounts() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(); val c = controller(api)
        c.startAll().join()
        assertEquals("2 started, 1 failed (5203: busy)", c.state.value.message)
        c.stopAll().join()
        assertEquals("1 stopped", c.state.value.message)
    }

    @Test fun logsAreIncrementalAndReloadAfterRotation() = runTest {
        val api = FakeApi().apply {
            logResponses.addAll(listOf(
                LogWindow(listOf("l1", "l2"), next = 2, total = 2),
                LogWindow(listOf("l3"), next = 3, total = 3),
                LogWindow(emptyList(), next = 1, total = 1), // rotated: total < since
                LogWindow(listOf("new1"), next = 1, total = 1),
            ))
        }
        val c = controller(api)
        c.openLogs(5201)
        runCurrent()
        assertEquals(listOf("l1", "l2"), c.state.value.logs!!.lines)
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf("l1", "l2", "l3"), c.state.value.logs!!.lines)
        assertEquals(3, c.state.value.logs!!.next)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("new1"), c.state.value.logs!!.lines)
        assertEquals(listOf("logs 5201 -", "logs 5201 2", "logs 5201 3", "logs 5201 -"), api.calls)
        c.setAutoRefresh(false)
        advanceTimeBy(5000); runCurrent()
        assertEquals(4, api.calls.size)
        c.closeLogs()
        assertNull(c.state.value.logs)
    }
}
