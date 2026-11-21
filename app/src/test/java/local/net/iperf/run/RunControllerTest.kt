package local.net.iperf.run

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.net.iperf.Fixtures
import local.net.iperf.SampleData
import local.net.iperf.core.IperfEvent
import local.net.iperf.core.IperfJson
import local.net.iperf.core.TestOptions
import local.net.iperf.data.TestRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunControllerTest {
    private class FakeEngine(val events: () -> Flow<IperfEvent>) : IperfEngine {
        var stops = 0
        var lastOptions: TestOptions? = null
        var lastAuth: local.net.iperf.core.IperfAuth? = null
        override fun run(options: TestOptions, auth: local.net.iperf.core.IperfAuth?): Flow<IperfEvent> { lastOptions = options; lastAuth = auth; return events() }
        override fun stop() { stops++ }
    }

    private val opts = TestOptions("127.0.0.1", 5299, durationSec = 3)

    @Test fun successfulRunSavesRecord() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<TestRecord>()
        val engine = FakeEngine { flowOf(*IperfJson.parseAll(Fixtures.text("tcp_upload.jsonl")).toTypedArray()) }
        val c = RunController(engine, this, { SampleData.wifi }, { saved += it }, clock = { 1234L })
        c.start(opts, "Loopback")
        advanceUntilIdle()
        val done = c.state.value as RunState.Done
        assertEquals(1, saved.size)
        assertEquals(done.record, saved.single())
        assertEquals(3, done.record.points.size)
        assertEquals("iperf 3.22", done.record.iperfVersion)
        assertEquals(SampleData.wifi, done.record.network)
        assertEquals(1234L, done.record.timestamp)
        assertFalse(done.record.stoppedEarly)
        assertEquals(opts, engine.lastOptions)
    }

    @Test fun busyServerFails() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<TestRecord>()
        val engine = FakeEngine { flowOf(*IperfJson.parseAll(Fixtures.text("busy.jsonl")).toTypedArray()) }
        val c = RunController(engine, this, { SampleData.wifi }, { saved += it })
        c.start(opts, "Loopback")
        advanceUntilIdle()
        val failed = c.state.value as RunState.Failed
        assertEquals("Server busy", failed.error.title)
        assertEquals(1, saved.size)
        assertTrue(failed.record.failed)
        assertEquals("Server busy", failed.record.errorTitle)
        assertEquals(failed.record, saved.single())
        c.dismiss()
        assertEquals(RunState.Idle, c.state.value)
    }

    @Test fun loginIsPassedToEngineAndRejectedLoginIsExplained() = runTest(UnconfinedTestDispatcher()) {
        val auth = local.net.iperf.core.IperfAuth("tester", "Example-Pass-123", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n")
        val engine = FakeEngine { flowOf(IperfEvent.Error("control socket has closed unexpectedly")) }
        val c = RunController(engine, this, { SampleData.wifi }, { })
        c.start(opts, "Auth server", auth)
        advanceUntilIdle()
        assertEquals(auth, engine.lastAuth)
        assertEquals("Login rejected", (c.state.value as RunState.Failed).error.title)
    }

    @Test fun liveIntervalsThenUserStopKeepsPartialResult() = runTest(UnconfinedTestDispatcher()) {
        val ch = Channel<IperfEvent>(Channel.UNLIMITED)
        val saved = mutableListOf<TestRecord>()
        val engine = FakeEngine { ch.consumeAsFlow() }
        val c = RunController(engine, this, { SampleData.wifi }, { saved += it })
        c.start(opts.copy(durationSec = 10), "Loopback")
        val all = IperfJson.parseAll(Fixtures.text("tcp_stopped_sigterm.jsonl"))
        ch.send(all[0]); ch.send(all[1]); ch.send(all[2])
        val running = c.state.value as RunState.Running
        assertTrue(running.connected)
        assertEquals(2, running.points.size)
        assertTrue(running.progress > 0f && running.progress < 1f)

        c.stop()
        assertEquals(1, engine.stops)
        assertTrue((c.state.value as RunState.Running).stopping)
        all.drop(3).forEach { ch.send(it) } // last interval, "interrupt" error, partial end
        ch.close()
        advanceUntilIdle()
        val done = c.state.value as RunState.Done
        assertTrue(done.record.stoppedEarly)
        assertEquals(3, done.record.points.size)
        assertEquals(2.4995, done.record.summary.durationSec!!, 0.001)
        assertEquals(1, saved.size)
    }

    @Test fun stopBeforeAnyDataStillSavesAStoppedRecord() = runTest(UnconfinedTestDispatcher()) {
        val ch = Channel<IperfEvent>(Channel.UNLIMITED)
        val saved = mutableListOf<TestRecord>()
        val c = RunController(FakeEngine { ch.consumeAsFlow() }, this, { SampleData.wifi }, { saved += it })
        c.start(opts, "Loopback")
        c.stop()
        ch.send(IperfEvent.Error("interrupt - the client has terminated by signal Terminated(15)"))
        ch.close()
        advanceUntilIdle()
        val done = c.state.value as RunState.Done
        assertTrue(done.record.stoppedEarly)
        assertFalse(done.record.failed)
        assertEquals(1, saved.size)
        assertTrue(done.record.points.isEmpty())
    }

    @Test fun exitWithoutResultsIsAnError() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<TestRecord>()
        val c = RunController(FakeEngine { flowOf() }, this, { SampleData.wifi }, { saved += it })
        c.start(opts, "Loopback")
        advanceUntilIdle()
        val failed = c.state.value as RunState.Failed
        assertEquals(1, saved.size)
        assertTrue(failed.record.failed)
        assertEquals(failed.record, saved.single())
    }

    @Test fun lostConnectionAfterIntervalsIsSavedWithPartialResults() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<TestRecord>()
        val ch = Channel<IperfEvent>(Channel.UNLIMITED)
        val c = RunController(FakeEngine { ch.consumeAsFlow() }, this, { SampleData.wifi }, { saved += it })
        c.start(opts, "Loopback")
        ch.send(IperfEvent.Started("iperf 3.22", null, null, null, null, 3))
        ch.send(IperfEvent.Interval(local.net.iperf.core.IntervalPoint(1.0, upMbps = 400.0)))
        ch.send(IperfEvent.Interval(local.net.iperf.core.IntervalPoint(2.0, upMbps = 410.0)))
        ch.send(IperfEvent.Error("error - unable to send control message: Broken pipe"))
        ch.close()
        advanceUntilIdle()
        val failed = c.state.value as RunState.Failed
        assertEquals(1, saved.size)
        assertTrue(failed.record.failed)
        assertEquals(2, failed.record.points.size)
        assertEquals(405.0, failed.record.summary.upload!!.mbps!!, 1e-9)
        assertFalse(failed.record.stoppedEarly)
    }

    @Test fun endlessRunKeepsAWindowAndStopSavesASummary() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<TestRecord>()
        val ch = Channel<IperfEvent>(Channel.UNLIMITED)
        val engine = FakeEngine { ch.consumeAsFlow() }
        val c = RunController(engine, this, { SampleData.wifi }, { saved += it })
        val endless = TestOptions("127.0.0.1", 5299, durationSec = 0)
        c.start(endless, "Loopback")
        ch.send(IperfEvent.Started("iperf 3.22", null, null, null, null, 0))
        repeat(1000) { i -> ch.send(IperfEvent.Interval(local.net.iperf.core.IntervalPoint((i + 1).toDouble(), upMbps = 50.0))) }
        val r = c.state.value as RunState.Running
        assertTrue(r.endless)
        assertEquals(1000.0, r.elapsedSec, 0.0)
        assertTrue("window ${r.points.size}", r.points.size <= 301)
        assertEquals(1000.0, r.latest!!.endSec, 0.0)
        c.stop()
        assertEquals(1, engine.stops)
        ch.close()
        advanceUntilIdle()
        val done = c.state.value as RunState.Done
        assertTrue(done.record.stoppedEarly)
        assertEquals(50.0, done.record.summary.upload!!.mbps!!, 1e-9)
        assertEquals(1000.0, done.record.points.last().endSec, 0.0)
        assertEquals(1, saved.size)
    }
}
