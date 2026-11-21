package local.net.iperf.ui.adaptive

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt

/** How the device is folded. TABLETOP = half open with a horizontal hinge (laptop-style); BOOK = half open, vertical hinge. */
enum class Posture { NORMAL, TABLETOP, BOOK }

/** A fold or hinge, in window pixels. */
data class Hinge(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val horizontal: Boolean,
    val separating: Boolean,
    val halfOpened: Boolean,
)

/** Window width class + fold posture. Compact phones (and the Fold's cover screen) keep the phone layout. */
data class Adaptive(
    val width: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    val posture: Posture = Posture.NORMAL,
    val hinge: Hinge? = null,
) {
    /** Medium or expanded: navigation rail and two-pane layouts. */
    val wide: Boolean get() = width != WindowWidthSizeClass.Compact
    val tabletop: Boolean get() = posture == Posture.TABLETOP

    companion object {
        val Compact = Adaptive()
        fun of(widthDp: Float, hinge: Hinge? = null) = Adaptive(widthClassOf(widthDp), postureOf(hinge), hinge)
    }
}

/** Material 3 breakpoints: < 600dp compact, < 840dp medium, otherwise expanded. */
fun widthClassOf(widthDp: Float): WindowWidthSizeClass = when {
    widthDp < 600f -> WindowWidthSizeClass.Compact
    widthDp < 840f -> WindowWidthSizeClass.Medium
    else -> WindowWidthSizeClass.Expanded
}

fun postureOf(hinge: Hinge?): Posture = when {
    hinge == null || !hinge.halfOpened -> Posture.NORMAL
    hinge.horizontal -> Posture.TABLETOP
    else -> Posture.BOOK
}

fun FoldingFeature.toHinge() = Hinge(
    bounds.left, bounds.top, bounds.right, bounds.bottom,
    horizontal = orientation == FoldingFeature.Orientation.HORIZONTAL,
    separating = isSeparating,
    halfOpened = state == FoldingFeature.State.HALF_OPENED,
)

val LocalAdaptive = staticCompositionLocalOf { Adaptive.Compact }

/**
 * Live size class + fold state for [activity]. Recomposes when the Fold is opened, closed or bent; the activity
 * handles those as configuration changes, so nothing (a running test, typed input) is recreated.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAdaptive(activity: Activity): Adaptive {
    val size = calculateWindowSizeClass(activity)
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = null)
    val hinge = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()?.toHinge()
    return Adaptive(size.widthSizeClass, postureOf(hinge), hinge)
}

/** Where a pane boundary goes: the hinge (if it lies inside [total], relative to [origin]) or [fraction] of [total]. */
internal fun splitAt(total: Int, fraction: Float, hingeStart: Int?, hingeEnd: Int?, origin: Float?, minGap: Int = 0): Pair<Int, Int> {
    if (hingeStart != null && hingeEnd != null && origin != null) {
        var s = (hingeStart - origin).roundToInt(); var e = (hingeEnd - origin).roundToInt()
        if (s > total / 5 && e < total - total / 5 && e >= s) {
            // A fold with no physical gap still gets a margin so no text or button sits on the crease.
            if (e - s < minGap) { val extra = (minGap - (e - s) + 1) / 2; s -= extra; e += extra }
            return s to e
        }
    }
    val s = (total * fraction).roundToInt()
    return s to s
}

/**
 * Two panes side by side. If a vertical hinge separates the window (book posture), the boundary sits on the hinge
 * and nothing is drawn under it; otherwise [firstFraction] of the width goes to [first].
 */
@Composable
fun TwoPane(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    firstFraction: Float = 0.4f,
    hinge: Hinge? = LocalAdaptive.current.hinge,
    /** Keeps forms usable: the first pane gets at least this much (if the second can still have as much). */
    firstMinWidth: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var origin by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().x }.testTag("twoPane")) {
        val h = hinge?.takeIf { !it.horizontal && it.separating }
        val d = LocalDensity.current
        val total = constraints.maxWidth
        val minPx = with(d) { firstMinWidth.roundToPx() }
        val fraction = if (h == null && minPx > 0 && total >= 2 * minPx) maxOf(firstFraction, minPx.toFloat() / total) else firstFraction
        val (s, e) = splitAt(total, fraction, h?.left, h?.right, origin, with(d) { 24.dp.roundToPx() })
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(with(d) { s.toDp() }).fillMaxHeight().testTag("paneFirst")) { first() }
            if (e > s) Spacer(Modifier.width(with(d) { (e - s).toDp() })) else VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight().testTag("paneSecond")) { second() }
        }
    }
}

/**
 * Tabletop: [top] above the horizontal hinge, [bottom] below it, nothing on the hinge itself. Without a usable
 * hinge it splits at [topFraction].
 */
@Composable
fun TopBottomPane(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    topFraction: Float = 0.5f,
    hinge: Hinge? = LocalAdaptive.current.hinge,
) {
    var origin by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().y }.testTag("tabletop")) {
        val h = hinge?.takeIf { it.horizontal }
        val d = LocalDensity.current
        val (s, e) = splitAt(constraints.maxHeight, topFraction, h?.top, h?.bottom, origin, with(d) { 24.dp.roundToPx() })
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(with(d) { s.toDp() }).fillMaxWidth().testTag("paneTop")) { top() }
            if (e > s) Spacer(Modifier.height(with(d) { (e - s).toDp() })) else HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().testTag("paneBottom")) { bottom() }
        }
    }
}
