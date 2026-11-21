package local.net.iperf.core

import local.net.iperf.ui.components.timeLabel
import local.net.iperf.ui.components.timeStep
import local.net.iperf.ui.test.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndlessTest {
    @Test fun durationZeroIsEndless() {
        val ok = OptionsDraft(duration = "0", omit = "3").toOptions("h", 5201) as OptionsDraft.Parsed.Ok
        assertTrue(ok.options.endless)
        val args = ok.options.toArgs()
        assertEquals("0", args[args.indexOf("-t") + 1])
        assertTrue(ok.options.describe().contains("endless"))
    }

    @Test fun negativeOrBlankDurationIsRejectedWithEndlessHint() {
        val bad = OptionsDraft(duration = "").toOptions("h", 5201) as OptionsDraft.Parsed.Invalid
        assertEquals(OptionsDraft.Field.DURATION, bad.field)
        assertTrue(bad.message.contains("0 = endless"))
        assertTrue(OptionsDraft(duration = "-5").toOptions("h", 5201) is OptionsDraft.Parsed.Invalid)
    }

    @Test fun bufferStaysBoundedAndSummaryIsExact() {
        val b = PointBuffer(maxPoints = 100, windowSec = 60)
        repeat(100_000) { i -> b.add(IntervalPoint((i + 1).toDouble(), upMbps = if (i % 2 == 0) 100.0 else 200.0)) }
        assertTrue("all ${b.all().size}", b.all().size <= 101)
        assertTrue(b.window().size in 60..61)
        assertEquals(100_000.0, b.all().last().endSec, 0.0)
        assertEquals(150.0, b.summary()!!.upload!!.mbps!!, 1e-9)
        assertEquals(100_000.0, b.summary()!!.durationSec!!, 0.0)
        assertEquals(100_000, b.count)
    }

    @Test fun summaryIgnoresOmittedIntervals() {
        val b = PointBuffer()
        b.add(IntervalPoint(1.0, downMbps = 1.0, omitted = true))
        b.add(IntervalPoint(2.0, downMbps = 9.0)); b.add(IntervalPoint(3.0, downMbps = 11.0))
        assertEquals(10.0, b.summary()!!.download!!.mbps!!, 1e-9)
        assertNull(b.summary()!!.upload)
        assertNull(PointBuffer().summary())
    }

    @Test fun elapsedAndAxisLabels() {
        assertEquals("0:09", formatElapsed(9.4))
        assertEquals("1:15", formatElapsed(75.0))
        assertEquals("1:02:05", formatElapsed(3725.0))
        assertEquals(60.0, timeStep(300.0), 0.0)
        assertEquals("5m", timeLabel(300.0, 60.0))
        assertEquals("10s", timeLabel(10.0, 5.0))
        assertEquals("2h", timeLabel(7200.0, 3600.0))
        assertTrue(timeStep(30_000.0) >= 3600.0)
    }
}
