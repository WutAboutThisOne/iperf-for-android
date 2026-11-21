package local.net.iperf.adaptive

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import local.net.iperf.ui.adaptive.Adaptive
import local.net.iperf.ui.adaptive.Hinge
import local.net.iperf.ui.adaptive.Posture
import local.net.iperf.ui.adaptive.postureOf
import local.net.iperf.ui.adaptive.splitAt
import local.net.iperf.ui.adaptive.widthClassOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLogicTest {
    @Test fun widthClasses() {
        assertEquals(WindowWidthSizeClass.Compact, widthClassOf(392f))   // Fold cover screen / phone
        assertEquals(WindowWidthSizeClass.Medium, widthClassOf(673f))    // Fold inner, portrait-ish
        assertEquals(WindowWidthSizeClass.Expanded, widthClassOf(841f))  // Fold inner, landscape
        assertFalse(Adaptive.of(392f).wide)
        assertTrue(Adaptive.of(673f).wide)
    }

    @Test fun postures() {
        assertEquals(Posture.NORMAL, postureOf(null))
        assertEquals(Posture.NORMAL, postureOf(Hinge(0, 900, 1800, 900, horizontal = true, separating = false, halfOpened = false)))
        assertEquals(Posture.TABLETOP, postureOf(Hinge(0, 900, 1800, 900, horizontal = true, separating = true, halfOpened = true)))
        assertEquals(Posture.BOOK, postureOf(Hinge(1000, 0, 1000, 1800, horizontal = false, separating = true, halfOpened = true)))
        assertTrue(Adaptive.of(841f, Hinge(0, 900, 1800, 900, true, true, true)).tabletop)
    }

    @Test fun splitFallsBackToFraction() {
        assertEquals(400 to 400, splitAt(1000, 0.4f, null, null, null))
        // Hinge too close to an edge (e.g. reported for another display area): ignored.
        assertEquals(500 to 500, splitAt(1000, 0.5f, 50, 60, 0f))
    }

    @Test fun splitSitsOnTheHingeWithAMargin() {
        // Window-space hinge at 1100..1100, pane starts at x=100: boundary at 1000 in pane space, widened to 24 px.
        val (s, e) = splitAt(2000, 0.4f, 1100, 1100, 100f, minGap = 24)
        assertTrue(s < 1000 && e > 1000)
        assertEquals(24, e - s, 1)
        // A physical hinge wider than the margin is used as-is.
        assertEquals(900 to 960, splitAt(2000, 0.4f, 1000, 1060, 100f, minGap = 24))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) = assertTrue("$actual vs $expected", kotlin.math.abs(expected - actual) <= tolerance)
}
