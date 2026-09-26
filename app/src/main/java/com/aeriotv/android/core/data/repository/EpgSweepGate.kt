package com.aeriotv.android.core.data.repository

import android.os.SystemClock

/**
 * Process-wide "is it a good moment to spend a background request?" gate for
 * the quiet EPG sweep in [PlaylistRepository.startEpgBackgroundSweep]
 * (Logan 2026-09-12).
 *
 * The sweep must be invisible: it pauses while the app is backgrounded and
 * while a tune is still waiting on its first frame, and resumes at the chunk
 * it stopped on. Two volatile flags polled by the sweep, deliberately not a
 * flow: nothing here should cost a subscription or a recomposition.
 */
object EpgSweepGate {
    /** Set from the app scaffold's ProcessLifecycle observer. Starts true so a
     *  cold launch (which is by definition foreground) never stalls the sweep
     *  waiting for an ON_START it already missed. */
    @Volatile
    var appInForeground: Boolean = true

    @Volatile
    private var tuneStartedAtMs: Long = 0L

    /** A tune has begun; the sweep holds off until its first frame. */
    fun onTuneStart() {
        tuneStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** First frame (or STATE_READY) landed; the sweep may spend requests again. */
    fun onTunePlaying() {
        tuneStartedAtMs = 0L
    }

    /**
     * True while a tune is before its first frame. Ceilinged at
     * [TUNE_MAX_MS]: a missed [onTunePlaying] (a tune that failed, a screen
     * torn down mid-prime) must not wedge the sweep for the life of the
     * process.
     */
    val tuneInProgress: Boolean
        get() {
            val started = tuneStartedAtMs
            return started != 0L && SystemClock.elapsedRealtime() - started < TUNE_MAX_MS
        }

    /**
     * Set while Dispatcharr live updates are active. The sweep then also waits
     * while something is being watched (Kodi's "prevent updates while
     * playing"): its bulk chunk writes underran a playing stream's audio on a
     * Shield, and live updates already keep the on-screen window current.
     * False (stock) when the setting is off.
     */
    @Volatile
    var holdWhileWatching: Boolean = false

    /** The one question the sweep asks between chunks. */
    val sweepAllowed: Boolean
        get() = appInForeground && !tuneInProgress &&
            !(holdWhileWatching && com.aeriotv.android.core.playback.PlaybackActivityTracker.watching.value)

    private const val TUNE_MAX_MS = 30_000L
}
