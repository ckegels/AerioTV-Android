package com.aeriotv.android.feature.player

import android.os.SystemClock

/**
 * Double-Back gate for the TV players (Logan 2026-09-14).
 *
 * Back model on Android TV:
 *   - FIRST Back  -> the existing behavior (live/DVR fullscreen minimizes to
 *                    the corner mini; a catch-up replay or the VOD player
 *                    exits). The press is armed here as it happens.
 *   - SECOND Back within [WINDOW_MS] -> end playback completely: the same
 *                    full-stop path the remote's X / companion CMD_STOP uses
 *                    (holder.stop() plus dismissing the mini session and the
 *                    background media service), then close the player.
 *
 * Process-level rather than a composable local on purpose: the minimizing
 * Back tears the player composition down and the follow-up press lands on a
 * DIFFERENT handler (TvMiniPlayerOverlay), so the timestamp has to outlive
 * both compositions. It also keeps VODPlayerScreen's top-level composable
 * free of new locals / lambdas.
 */
object PlayerDoubleBack {
    /** Logan's spec: a second Back inside 700 ms is "Back twice". */
    const val WINDOW_MS = 700L

    @Volatile
    private var armedAt = 0L

    /** Record the Back that minimized (or closed) the player. */
    fun arm() {
        armedAt = SystemClock.uptimeMillis()
    }

    /**
     * True when this Back arrived within [WINDOW_MS] of the armed one, i.e.
     * the user pressed Back twice and wants playback to end. Always consumes
     * the armed press, so a third Back starts over.
     */
    fun isSecondPress(): Boolean {
        val armed = armedAt
        armedAt = 0L
        return armed != 0L && (SystemClock.uptimeMillis() - armed) <= WINDOW_MS
    }

    /** Forget any armed press (playback ended by some other route). */
    fun clear() {
        armedAt = 0L
    }
}
