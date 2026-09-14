package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.aeriotv.android.feature.player.PlayerDoubleBack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV mini-player Back handling (tvOS NowPlayingManager parity).
 *
 * The VIDEO is drawn by the activity-lifetime PersistentExoWindow at
 * MainActivity root; this composable contributes ONLY the Back semantics
 * while the mini is Active. There is deliberately no on-screen chip here:
 * tvOS shows the resume/return hints on the GUIDE (top-left) instead, which
 * GuideScreen renders (see the guide gesture-hint chips).
 *
 * Back model (matches tvOS `handleMenuPress` mini branch, 0.3s debounce):
 *   - SINGLE Back  -> expand the mini back to fullscreen ([onResume]).
 *   - DOUBLE Back  -> jump the guide to the top channel ([onJumpToTop]); the
 *                     mini stays Active and keeps playing.
 * The one exception (Logan 2026-09-14): a Back that lands within
 * [PlayerDoubleBack.WINDOW_MS] of the Back that minimized the player is the
 * second half of a "Back twice" and ends playback completely ([onStop]) - no
 * mini, stream stopped, back on the page the user came from. Any later Back
 * follows the resume / jump-to-top model above. Play/Pause = resume is
 * handled in MainActivity (KEYCODE_MEDIA_PLAY_PAUSE).
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    onResume: () -> Unit,
    onJumpToTop: () -> Unit,
    onStop: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return

    // Single/double Back debounce (tvOS uses 300ms). A short Back that isn't
    // followed by a second within the window resumes; two within the window
    // jump the guide to the top channel. onPreviewKeyEvent isn't needed -- the
    // guide's own BackHandler is disabled while the mini is Active, so this is
    // the sole Back handler in this state.
    val scope = rememberCoroutineScope()
    var pressCount by remember { mutableIntStateOf(0) }
    var debounce by remember { mutableStateOf<Job?>(null) }
    BackHandler {
        // "Back twice" close: this press followed the minimizing Back inside
        // the window, so end playback outright instead of expanding.
        if (PlayerDoubleBack.isSecondPress()) {
            debounce?.cancel()
            debounce = null
            pressCount = 0
            onStop()
            return@BackHandler
        }
        pressCount++
        debounce?.cancel()
        debounce = scope.launch {
            delay(300)
            val isDouble = pressCount >= 2
            pressCount = 0
            if (isDouble) onJumpToTop() else onResume()
        }
    }
}
