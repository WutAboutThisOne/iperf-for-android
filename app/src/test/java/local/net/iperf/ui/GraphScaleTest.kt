package local.net.iperf.ui

import local.net.iperf.ui.components.axisScale
import local.net.iperf.ui.components.niceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphScaleTest {
    @Test fun niceSteps() {
        assertEquals(1.0, niceStep(0.8), 1e-9)
        assertEquals(2.0, niceStep(1.3), 1e-9)
        assertEquals(50.0, niceStep(41.0), 1e-9)
        assertEquals(1000.0, niceStep(720.0), 1e-9)
    }

    @Test fun scalesHaveRoundTicks() {
        for (peak in listOf(0.0, 0.7, 9.3, 47.9, 199.0, 655.0, 941.0, 2370.0, 65000.0)) {
            val (max, step) = axisScale(peak)
            val ticks = max / step
            assertTrue("peak $peak → $max/$step", max >= peak && ticks in 1.99..5.01 && kotlin.math.abs(ticks - Math.rint(ticks)) < 1e-6)
        }
        assertEquals(300.0 to 100.0, axisScale(199.0))
    }
}
