package com.aeriotv.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import com.aeriotv.android.feature.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Letterbox / pillarbox the stream inside the surface (default). */
const val VIDEO_SCALE_FIT = "fit"

/**
 * Scale up preserving aspect until the shorter dimension fills the surface,
 * cropping the overflow. On a 4:3 display this removes pillar bars baked
 * into a 16:9 stream.
 */
const val VIDEO_SCALE_FILL = "fill"

/** Stretch the stream to the surface, ignoring the source aspect ratio. */
const val VIDEO_SCALE_STRETCH = "stretch"

/** Pinch-out ratio that commits to Fill. */
private const val PINCH_OUT_RATIO = 1.15f

/** Pinch-in ratio that commits to Fit. */
private const val PINCH_IN_RATIO = 0.85f

/** How long the centered "Fit" / "Fill" label stays up after a pinch. */
private const val SCALE_LABEL_MS = 1_000L

fun videoScaleLabel(mode: String): String = when (mode) {
    VIDEO_SCALE_FILL -> "Fill"
    VIDEO_SCALE_STRETCH -> "Stretch"
    else -> "Fit"
}

/** Options row order: Fit -> Fill -> Stretch -> Fit. */
fun nextVideoScaleMode(current: String): String = when (current) {
    VIDEO_SCALE_FIT -> VIDEO_SCALE_FILL
    VIDEO_SCALE_FILL -> VIDEO_SCALE_STRETCH
    else -> VIDEO_SCALE_FIT
}

/**
 * Map a Cast / companion CMD_SET_ASPECT key onto a video scale mode. The wire
 * protocol predates this setting: its "zoom" is our Fill (crop, aspect kept)
 * and its "fill" is our Stretch.
 */
fun videoScaleFromCastAspectKey(key: String?): String = when (key) {
    "zoom" -> VIDEO_SCALE_FILL
    "fill" -> VIDEO_SCALE_STRETCH
    else -> VIDEO_SCALE_FIT
}

/** Inverse of [videoScaleFromCastAspectKey], for the receiver's state reply. */
fun castAspectKeyFromVideoScale(mode: String): String = when (mode) {
    VIDEO_SCALE_FILL -> "zoom"
    VIDEO_SCALE_STRETCH -> "fill"
    else -> "fit"
}

/**
 * Resolve the Media3 resize mode for a player surface.
 *
 * Fill maps to RESIZE_MODE_ZOOM (aspect preserved, overflow cropped) and
 * Stretch to RESIZE_MODE_FILL (aspect ignored). Picture in Picture always
 * stays Fit: the PiP window is already letterboxed to the source aspect, so
 * cropping or stretching there only hurts.
 */
fun videoScaleResizeMode(
    scaleMode: String,
    inPip: Boolean = false,
): Int = when {
    inPip -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    scaleMode == VIDEO_SCALE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    scaleMode == VIDEO_SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * Transparent pinch layer for touch devices: pinch out sets Fill, pinch in
 * sets Fit, with a brief centered label. Pointer events are never consumed,
 * so the tap / vertical drag layers underneath keep working; only the
 * two-finger case is interpreted here.
 *
 * Both players host this in one line because their top-level composables sit
 * near the JVM verifier's register limit, so all of the state lives here.
 */
@Composable
fun BoxScope.VideoScalePinchLayer(
    settingsVm: SettingsViewModel,
    enabled: Boolean = true,
) {
    if (!enabled) return
    val scaleMode by settingsVm.videoScaleMode
        .collectAsStateWithLifecycle(initialValue = VIDEO_SCALE_FIT)
    var labelText by remember { mutableStateOf(VIDEO_SCALE_FIT) }
    var labelSerial by remember { mutableIntStateOf(0) }
    var labelVisible by remember { mutableStateOf(false) }

    LaunchedEffect(labelSerial) {
        if (labelSerial == 0) return@LaunchedEffect
        labelVisible = true
        delay(SCALE_LABEL_MS)
        labelVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(scaleMode) {
                awaitPointerEventScope {
                    while (true) {
                        var event = awaitPointerEvent()
                        if (event.changes.size < 2) continue
                        val start = pinchSpan(event.changes[0].position, event.changes[1].position)
                        var ratio = 1f
                        while (event.changes.size >= 2 && event.changes.any { it.pressed }) {
                            event = awaitPointerEvent()
                            if (event.changes.size >= 2 && start > 0f) {
                                ratio = pinchSpan(
                                    event.changes[0].position,
                                    event.changes[1].position,
                                ) / start
                            }
                        }
                        val next = when {
                            ratio > PINCH_OUT_RATIO -> VIDEO_SCALE_FILL
                            ratio < PINCH_IN_RATIO -> VIDEO_SCALE_FIT
                            else -> null
                        }
                        if (next != null) {
                            settingsVm.setVideoScaleMode(next)
                            labelText = videoScaleLabel(next)
                            labelSerial++
                        }
                    }
                }
            },
    )

    AnimatedVisibility(
        visible = labelVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Text(
            text = labelText,
            fontSize = 28.sp,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

private fun pinchSpan(
    a: androidx.compose.ui.geometry.Offset,
    b: androidx.compose.ui.geometry.Offset,
): Float = hypot((a.x - b.x), (a.y - b.y))

/**
 * "Video Scale: Fit / Fill / Stretch" row for the on-demand options sheet. Takes the
 * view model rather than a callback so the caller adds no lambda of its own
 * (VODPlayerScreen register pressure). Tapping cycles in place; the sheet
 * stays open so the change is visible behind it.
 */
@Composable
fun VideoScaleOptionRow(settingsVm: SettingsViewModel) {
    val scaleMode by settingsVm.videoScaleMode
        .collectAsStateWithLifecycle(initialValue = VIDEO_SCALE_FIT)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { settingsVm.cycleVideoScaleMode(scaleMode) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Video Scale: ${videoScaleLabel(scaleMode)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
