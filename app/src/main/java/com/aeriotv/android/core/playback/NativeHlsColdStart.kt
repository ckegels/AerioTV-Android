package com.aeriotv.android.core.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * Cold-start patience for the native HLS live path (Apple parity).
 *
 * Dispatcharr's HLS output publishes a per-client media playlist the moment the
 * client is minted, BEFORE the provider upstream has connected. On a cold
 * channel that playlist can sit at zero or one segment for well over 12 s while
 * the provider locks, and only then starts listing ~4 s segments under
 * TARGETDURATION 6. Media3's own stuck detector (3.5 x target duration) and the
 * holder's ladders would both give up inside that window and tear down the one
 * client the tune is allowed to mint.
 *
 * So a cold native-HLS tune is judged by PROGRESS, not by elapsed time:
 *
 *  - while the client playlist is still GROWING (more segments than the last
 *    poll, a higher media sequence, or fewer than [MIN_SEGMENTS] segments,
 *    which reads as "the server has not published a playable window yet"),
 *    keep waiting up to [COLD_START_MAX_MS] in total;
 *  - give up as soon as the playlist stops growing for [COLD_START_STALL_MS],
 *    or a 4xx / 5xx arrives, whichever comes first.
 *
 * Giving up hands the tune back to the holder's normal ladder and sets
 * [forceTsNextTune], so the retry re-tunes on the TS remux path rather than
 * minting a second HLS client against a server that is clearly not producing
 * one.
 *
 * WARM CHANNELS ARE UNAFFECTED: a warm channel's first playlist already lists a
 * full window, the first poll satisfies [MIN_SEGMENTS], the first frame renders
 * and [disarm] fires long before any of these deadlines matter. The TS path
 * never arms this at all, so [LiveStreamFailover]'s timings are untouched.
 */
object NativeHlsColdStart {

    private const val TAG = "AerioPlayer"

    /** Total patience for a cold tune whose playlist keeps growing. */
    const val COLD_START_MAX_MS = 30_000L

    /** Growth has to appear at least this often, or the tune is abandoned. */
    const val COLD_START_STALL_MS = 12_000L

    /** Below this, a playlist counts as "still filling" even if it has not
     *  grown since the last poll: there is no playable window yet. */
    const val MIN_SEGMENTS = 3

    /**
     * Media3's playlist-stuck coefficient for this path. TARGETDURATION 6 x 5.0
     * = 30 s, so the tracker's own PlaylistStuckException can never fire before
     * [COLD_START_MAX_MS]; the rule above owns the decision instead.
     */
    const val PLAYLIST_STUCK_COEFFICIENT = 5.0

    /** What a short-window join waits for before audio and video are let go. */
    const val SHORT_WINDOW_TARGET_AHEAD_MS = 6_000L

    /** And the longest it may wait, however thin the window stays. */
    const val SHORT_WINDOW_MAX_HOLD_MS = 8_000L

    @Volatile private var armedAtMs = 0L
    @Volatile private var lastGrowthAtMs = 0L
    @Volatile private var lastSegmentCount = -1
    @Volatile private var lastMediaSequence = -1L
    @Volatile private var channel: String = "?"

    /** Wall of this tune's prime, so the [HLS-BUF] trace can stamp +Ns. 0 = no
     *  native-HLS tune in flight. */
    @Volatile private var tuneStartedAtMs = 0L

    /** The playlist window (sum of segment durations) and TARGETDURATION of the
     *  FIRST client playlist of this tune, in ms. 0 = not measured yet. */
    @Volatile private var joinWindowMs = 0L
    @Volatile private var joinTargetDurationMs = 0L

    /**
     * Set when a cold start was abandoned, consumed by the next live tune so it
     * opens on the TS path. One shot: it never survives the tune it steers.
     */
    @Volatile var forceTsNextTune: Boolean = false

    /** True while a cold native-HLS tune is still inside its patience window. */
    fun isWaiting(): Boolean = armedAtMs != 0L

    /** A native-HLS live tune has just been primed. */
    fun arm(channelName: String?) {
        val now = SystemClock.elapsedRealtime()
        armedAtMs = now
        lastGrowthAtMs = now
        lastSegmentCount = -1
        lastMediaSequence = -1L
        channel = channelName ?: "?"
        tuneStartedAtMs = now
        joinWindowMs = 0L
        joinTargetDurationMs = 0L
    }

    /** Elapsed ms since this native-HLS tune was primed, or -1 when none is. */
    fun sinceTuneMs(): Long {
        val started = tuneStartedAtMs
        return if (started == 0L) -1L else SystemClock.elapsedRealtime() - started
    }

    /** The tune is over (teardown / channel change): stop the [HLS-BUF] trace. */
    fun endTune() {
        tuneStartedAtMs = 0L
    }

    /** The playlist window measured at join, in ms (0 = never measured). */
    fun joinWindowMs(): Long = joinWindowMs

    /**
     * True when the window the tune JOINED was shorter than three target
     * durations. Dispatcharr publishes a cold channel's first window as about
     * six fast-start segments (roughly 15 s) and #EXT-X-START:TIME-OFFSET=-10
     * joins about 2 s from the live edge, so the first frame paints and then
     * freezes while the player builds a cushion out of a window that barely has
     * one. A warm join lists a full window and never takes this path.
     */
    fun isShortWindow(): Boolean {
        val window = joinWindowMs
        val target = joinTargetDurationMs
        return window > 0L && target > 0L && window < target * 3L
    }

    /** How much media the short-window hold waits for: 6 s, or half the window
     *  when the window itself cannot supply 6 s. */
    fun shortWindowTargetAheadMs(): Long =
        minOf(SHORT_WINDOW_TARGET_AHEAD_MS, joinWindowMs / 2L).coerceAtLeast(0L)

    /** First frame, teardown, channel change, or a fall back to TS. */
    fun disarm(reason: String) {
        if (armedAtMs == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - armedAtMs
        armedAtMs = 0L
        Log.i(TAG, "[HLS] cold start: done ($reason) at +${elapsed / 1000}s ch=$channel")
    }

    /** The first playlist of a tune records the window the player joins on. */
    fun onJoinWindow(windowMs: Long, targetDurationMs: Long) {
        if (tuneStartedAtMs == 0L || joinWindowMs != 0L) return
        if (windowMs <= 0L || targetDurationMs <= 0L) return
        joinWindowMs = windowMs
        joinTargetDurationMs = targetDurationMs
    }

    /** Every media playlist Media3 parses on this tune, growth or not. */
    fun onPlaylist(segmentCount: Int, mediaSequence: Long) {
        val armed = armedAtMs
        if (armed == 0L) return
        val now = SystemClock.elapsedRealtime()
        val grew = segmentCount > lastSegmentCount ||
            mediaSequence > lastMediaSequence ||
            segmentCount < MIN_SEGMENTS
        lastSegmentCount = segmentCount
        lastMediaSequence = mediaSequence
        if (!grew) return
        lastGrowthAtMs = now
        Log.i(
            TAG,
            "[HLS] cold start: playlist growing ($segmentCount segments) " +
                "at +${(now - armed) / 1000}s",
        )
    }

    /** A definitive server answer on the playlist or a segment. */
    fun onHttpError(code: Int) {
        if (armedAtMs == 0L) return
        if (code < 400) return
        Log.w(TAG, "[HLS] cold start: HTTP $code ch=$channel; abandoning the HLS path")
        giveUp("http $code")
    }

    /**
     * Polled by the holder's watchdog. True means the patience window is spent
     * and the caller should run its normal retry ladder; the cold start is
     * already disarmed and the next tune is pinned to TS.
     */
    fun isExpired(): Boolean {
        val armed = armedAtMs
        if (armed == 0L) return false
        val now = SystemClock.elapsedRealtime()
        return when {
            now - armed >= COLD_START_MAX_MS -> giveUp("no first frame in ${COLD_START_MAX_MS / 1000}s")
            now - lastGrowthAtMs >= COLD_START_STALL_MS ->
                giveUp("playlist stopped growing for ${COLD_START_STALL_MS / 1000}s")
            else -> false
        }
    }

    private fun giveUp(reason: String): Boolean {
        val armed = armedAtMs
        if (armed == 0L) return false
        armedAtMs = 0L
        forceTsNextTune = true
        Log.w(
            TAG,
            "[HLS] cold start: giving up ($reason) at " +
                "+${(SystemClock.elapsedRealtime() - armed) / 1000}s ch=$channel; falling back to TS",
        )
        return true
    }

    /** Consumed by the tune path: one shot, then back to the measured engine. */
    fun consumeForceTs(): Boolean {
        if (!forceTsNextTune) return false
        forceTsNextTune = false
        return true
    }
}

/**
 * Wraps Media3's own parser so every client-playlist poll is reported to
 * [NativeHlsColdStart]. It adds NO request: the tracker was going to parse
 * these bytes anyway, which is the whole point of measuring growth here rather
 * than polling the playlist ourselves (a second request would mint a second
 * server-side client).
 */
class NativeHlsColdStartParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        Reporting(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        Reporting(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private class Reporting(
        private val inner: ParsingLoadable.Parser<HlsPlaylist>,
    ) : ParsingLoadable.Parser<HlsPlaylist> {
        override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
            val playlist = inner.parse(uri, inputStream)
            if (playlist is HlsMediaPlaylist) {
                NativeHlsColdStart.onPlaylist(playlist.segments.size, playlist.mediaSequence)
                NativeHlsColdStart.onJoinWindow(
                    playlist.segments.sumOf { it.durationUs } / 1_000L,
                    playlist.targetDurationUs / 1_000L,
                )
            }
            return playlist
        }
    }
}

/**
 * The native-HLS load-error policy: the TS path's 503 / connection-limit
 * handling, plus a report of any definitive 4xx / 5xx to [NativeHlsColdStart]
 * so a cold start stops waiting on a server that has already answered.
 */
class NativeHlsLoadErrorPolicy : Live503LoadErrorPolicy() {
    override fun getRetryDelayMsFor(
        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        val responseCode =
            (loadErrorInfo.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                ?.responseCode
        if (responseCode != null) NativeHlsColdStart.onHttpError(responseCode)
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
