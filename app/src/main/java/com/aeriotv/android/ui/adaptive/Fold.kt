// Fold.kt
//
// Settings redesign phase 2, item 2: fold awareness.
//
// A two-pane layout on a foldable must not put a pane boundary anywhere except
// ON the crease. Splitting the window at a fixed 280dp on an unfolded Pixel
// Fold puts the divider a couple of centimeters to the left of the hinge, so
// the detail pane is the one that gets folded in half. Reading the hinge out of
// WindowLayoutInfo lets the sidebar end exactly where the crease begins.
//
// Only a VERTICAL fold matters here (the hinge runs top-to-bottom, splitting
// the window left/right: book posture, and the same device lying flat). A
// HORIZONTAL fold is tabletop posture, which splits top/bottom; Settings stays
// side by side there and ignores it, per the plan.
//
// Half-opened and flat are treated identically. In both the hinge occupies a
// real place in the window; the only difference is whether its bounds have
// width (half-opened reports a separating feature, flat usually reports a
// zero-width or hairline one), and the layout math below handles either.

package com.aeriotv.android.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * A vertical hinge, expressed in dp from the leading edge of the window.
 *
 * [start] is where the crease begins (so a left pane may be that wide) and
 * [end] is where it stops (so a right pane may start there). On a flat fold
 * the two are usually equal.
 */
data class VerticalFold(val start: Dp, val end: Dp) {
    /** Width of the crease itself; 0 on a flat fold. */
    val gap: Dp get() = (end - start).coerceAtLeast(0.dp)
}

/**
 * The window's current vertical fold, or null when there is none (a phone, a
 * tablet, a folded foldable, or a tabletop posture whose hinge is horizontal).
 *
 * Returns null on any device without the extension backing WindowLayoutInfo,
 * which is every non-foldable, so callers simply fall back to fixed widths.
 */
@Composable
fun rememberVerticalFold(): VerticalFold? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // No early return above this point: every remember/produceState below has
    // to be reached on every composition, or the slot table sees a different
    // shape once a fold appears.
    val feature by produceState<FoldingFeature?>(initialValue = null, activity, lifecycleOwner) {
        val host = activity ?: return@produceState
        // STARTED rather than a bare collect: posture updates arriving while
        // the app is backgrounded are stale by the time it returns, and the
        // tracker stops emitting anyway.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(host)
                .windowLayoutInfo(host)
                .collect { info ->
                    value = info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
                }
        }
    }

    val bounds = feature?.bounds ?: return null
    // A degenerate feature (no extent at all) is not a usable boundary.
    if (bounds.height() <= 0 && bounds.width() <= 0) return null
    // Cheap enough to build every frame; deliberately NOT remembered, so this
    // function has the same slot shape with and without a fold.
    return with(density) {
        VerticalFold(start = bounds.left.toDp(), end = bounds.right.toDp())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
