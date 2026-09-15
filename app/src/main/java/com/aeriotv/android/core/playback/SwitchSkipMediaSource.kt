package com.aeriotv.android.core.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Live raw-TS source wrapper that can drop the OLD stream's buffered samples
 * after a Dispatcharr upstream switch, on the same connection.
 *
 * Why not a seek: on Media3 1.4.1 a progressive live period has an unseekable
 * SeekMap and DATA_TYPE_MEDIA_PROGRESSIVE_LIVE, so ProgressiveMediaPeriod.seekToUs
 * maps every seek to 0, discards all samples, cancels the loader and opens a new
 * GET. A second GET is exactly what makes Dispatcharr (stream_limit 1) terminate
 * our client and restart the channel on its default stream.
 *
 * Instead, once new data is queued past the boundary, the wrapped
 * SampleStreams use the sample queue's own keyframe skip (no loader
 * involvement): video lands on a keyframe inside the new data, audio on that
 * keyframe's time. Reads are NEVER withheld: until the jump (or if it cannot
 * happen) every read is a normal read and the old buffer plays out.
 *
 * (A hard switch that held reads and reset the renderers was tried and
 * abandoned 2026-09-15: MediaCodec is not reconfigured across the in-place
 * upstream change, so the picture came up green or purple.)
 *
 * All skip state lives on the playback thread (requests are posted to it).
 */
@UnstableApi
class SwitchSkipMediaSource(inner: MediaSource) : WrappingMediaSource(inner) {

    sealed class Result {
        /** Old samples dropped; playback continues at [boundaryOffsetMs] past the boundary. */
        data class Jumped(val boundaryOffsetMs: Long, val droppedMs: Long) : Result()
        /** The playhead was already at or past the boundary; nothing to drop. */
        object AlreadyPast : Result()
        /** Skip gave up; the old buffer plays out as before. */
        data class Abandoned(val reason: String) : Result()
    }

    @Volatile private var period: SwitchSkipMediaPeriod? = null

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val p = SwitchSkipMediaPeriod(super.createPeriod(id, allocator, startPositionUs))
        period = p
        return p
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val p = mediaPeriod as SwitchSkipMediaPeriod
        if (period === p) period = null
        p.abandon("period released")
        super.releasePeriod(p.inner)
    }

    /**
     * Arm a skip on the current period. [playbackHandler] runs on the player's
     * playback looper; [onResult] is invoked on [resultHandler]. Arms once new
     * media reaches [minNewDataMs] past the boundary; gives up after [timeoutMs].
     * Returns false when there is no period to skip on.
     */
    fun requestSkip(
        playbackHandler: Handler,
        resultHandler: Handler,
        minNewDataMs: Long,
        timeoutMs: Long,
        onResult: (Result) -> Unit,
    ): Boolean {
        val p = period ?: return false
        playbackHandler.post {
            p.arm(minNewDataMs * 1000L, timeoutMs) { r -> resultHandler.post { onResult(r) } }
        }
        return true
    }
}

@UnstableApi
internal class SwitchSkipMediaPeriod(val inner: MediaPeriod) : MediaPeriod, MediaPeriod.Callback {

    private companion object {
        /** How long skip attempts are retried once new data is queued. */
        const val ACTIVE_TIMEOUT_MS = 5_000L
        /** How far into the new data the video keyframe skip may look. */
        const val LOOKAHEAD_US = 2_000_000L
    }

    private var callback: MediaPeriod.Callback? = null
    private var wrappers: Array<SkipStream?> = emptyArray()
    private val scratch = DecoderInputBuffer.newNoDataInstance()

    // ---- skip state (playback thread) ----
    private var armed = false
    private var active = false
    private var boundaryUs = C.TIME_UNSET
    private var minNewDataUs = 0L
    private var armedAtMs = 0L
    private var activatedAtMs = 0L
    private var timeoutMs = 0L
    private var targetUs = C.TIME_UNSET
    private var firstDroppedUs = C.TIME_UNSET
    private var resultSink: ((SwitchSkipMediaSource.Result) -> Unit)? = null

    fun arm(minNewDataUs: Long, timeoutMs: Long, sink: (SwitchSkipMediaSource.Result) -> Unit) {
        abandon("superseded by a new switch")
        val buffered = inner.bufferedPositionUs
        if (buffered == C.TIME_UNSET || buffered == C.TIME_END_OF_SOURCE) {
            sink(SwitchSkipMediaSource.Result.Abandoned("no buffered position"))
            return
        }
        this.boundaryUs = buffered
        this.minNewDataUs = minNewDataUs
        this.timeoutMs = timeoutMs
        armedAtMs = SystemClock.elapsedRealtime()
        targetUs = C.TIME_UNSET
        firstDroppedUs = C.TIME_UNSET
        wrappers.forEach { it?.done = false }
        resultSink = sink
        armed = true
        active = false
    }

    /** True while a skip is armed (waiting for new data or retrying). */
    val isArmed: Boolean get() = armed

    fun abandon(reason: String) {
        if (!armed) return
        finish(SwitchSkipMediaSource.Result.Abandoned(reason))
    }

    private fun finish(result: SwitchSkipMediaSource.Result) {
        armed = false
        active = false
        val sink = resultSink
        resultSink = null
        sink?.invoke(result)
    }

    private fun hasVideo(): Boolean = wrappers.any { it?.trackType == C.TRACK_TYPE_VIDEO }

    /** Arm -> active once the first post-switch data is loaded; enforce the timeouts. */
    private fun maybeActivate(): Boolean {
        if (!armed) return false
        val now = SystemClock.elapsedRealtime()
        if (!active && now - armedAtMs > timeoutMs) {
            abandon("no new data in ${timeoutMs}ms")
            return false
        }
        if (active && now - activatedAtMs > ACTIVE_TIMEOUT_MS) {
            // Reads are never withheld, so this only ends the retries.
            abandon("no keyframe past the boundary in ${ACTIVE_TIMEOUT_MS}ms")
            return false
        }
        if (!active) {
            val buffered = inner.bufferedPositionUs
            if (buffered != C.TIME_END_OF_SOURCE && buffered >= boundaryUs + minNewDataUs) {
                active = true
                activatedAtMs = now
            }
        }
        return active
    }

    private fun read(
        w: SkipStream,
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        if (maybeActivate() && !w.done &&
            (w.trackType == C.TRACK_TYPE_VIDEO || w.trackType == C.TRACK_TYPE_AUDIO) &&
            (readFlags and SampleStream.FLAG_PEEK) == 0
        ) {
            val formatResult = trySkip(w, formatHolder)
            if (formatResult != null) return formatResult
        }
        // Always a normal read: the skip never withholds samples, so the old
        // buffer keeps playing until (and unless) the jump happens.
        return w.inner.readData(formatHolder, buffer, readFlags)
    }

    /** Peek the next queued sample's time without consuming it. Returns
     *  C.TIME_UNSET when nothing is queued. A pending format is handed back via
     *  [formatOut] (the queue marks it delivered, so the renderer must get it). */
    private fun peekTimeUs(w: SkipStream, formatHolder: FormatHolder, formatOut: IntArray): Long {
        scratch.clear()
        val r = w.inner.readData(
            formatHolder, scratch, SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA,
        )
        if (r == C.RESULT_FORMAT_READ) { formatOut[0] = r; return C.TIME_UNSET }
        if (r != C.RESULT_BUFFER_READ || scratch.isEndOfStream) return C.TIME_UNSET
        return scratch.timeUs
    }

    /**
     * One NON-BLOCKING skip attempt for [w], using the queue's own keyframe
     * skip (SampleStream.skipData lands on the last keyframe at or before the
     * target, and never past what is queued). Returns a format result that must
     * be handed to the renderer now, or null to continue with a normal read.
     * Video goes first: it lands on a keyframe inside the new data, and audio
     * follows to that time. Audio never waits for video; it just reads normally
     * until the video target exists. A video attempt that lands short of the
     * boundary is retried on later reads (a later keyframe in range can only be
     * new data) until [ACTIVE_TIMEOUT_MS].
     */
    private fun trySkip(w: SkipStream, formatHolder: FormatHolder): Int? {
        val fmt = intArrayOf(C.RESULT_NOTHING_READ)
        val first = peekTimeUs(w, formatHolder, fmt)
        if (fmt[0] == C.RESULT_FORMAT_READ) return fmt[0]
        if (first == C.TIME_UNSET) return null
        val isVideo = w.trackType == C.TRACK_TYPE_VIDEO
        val video = hasVideo()
        if (!isVideo && video && targetUs == C.TIME_UNSET) return null
        if (first >= boundaryUs && (isVideo || !video)) {
            // Already playing past the boundary: nothing (more) to drop.
            w.done = true
            if (targetUs == C.TIME_UNSET) targetUs = first
            maybeFinish()
            return null
        }
        val skipTo = if (isVideo || !video) boundaryUs + LOOKAHEAD_US else targetUs
        if (first >= skipTo) {
            w.done = true
            maybeFinish()
            return null
        }
        if (w.inner.skipData(skipTo) == 0) return null // nothing new in range yet; retry later
        val landed = peekTimeUs(w, formatHolder, fmt)
        if (firstDroppedUs == C.TIME_UNSET && (isVideo || !video)) firstDroppedUs = first
        if (fmt[0] == C.RESULT_FORMAT_READ) {
            // Position already moved; deliver the new format, finish next read.
            return fmt[0]
        }
        if (isVideo || !video) {
            if (landed == C.TIME_UNSET || landed < boundaryUs) {
                // Only an old keyframe so far; retry on later reads.
                return null
            }
            targetUs = landed
        }
        w.done = true
        maybeFinish()
        return null
    }

    private fun maybeFinish() {
        if (!armed) return
        val av = wrappers.filterNotNull()
            .filter { it.trackType == C.TRACK_TYPE_VIDEO || it.trackType == C.TRACK_TYPE_AUDIO }
        if (av.isEmpty() || av.any { !it.done }) return
        val result = if (firstDroppedUs == C.TIME_UNSET || targetUs == C.TIME_UNSET) {
            SwitchSkipMediaSource.Result.AlreadyPast
        } else {
            SwitchSkipMediaSource.Result.Jumped(
                boundaryOffsetMs = (targetUs - boundaryUs) / 1000L,
                droppedMs = (targetUs - firstDroppedUs) / 1000L,
            )
        }
        finish(result)
    }

    inner class SkipStream(val inner: SampleStream, val trackType: Int) : SampleStream {
        var done = false
        override fun isReady(): Boolean = inner.isReady
        override fun maybeThrowError() = inner.maybeThrowError()
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            read(this, formatHolder, buffer, readFlags)
        override fun skipData(positionUs: Long): Int = inner.skipData(positionUs)
    }

    // ---- MediaPeriod ----

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        inner.prepare(this, positionUs)
    }

    override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = inner.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        inner.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val old = arrayOfNulls<SkipStream>(streams.size)
        val innerStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            val s = streams[i]
            if (s is SkipStream) { old[i] = s; innerStreams[i] = s.inner } else innerStreams[i] = s
        }
        val pos = inner.selectTracks(selections, mayRetainStreamFlags, innerStreams, streamResetFlags, positionUs)
        val next = arrayOfNulls<SkipStream>(streams.size)
        for (i in streams.indices) {
            val s = innerStreams[i]
            next[i] = when {
                s == null -> null
                old[i]?.inner === s -> old[i]
                else -> {
                    val mime = selections[i]?.selectedFormat?.sampleMimeType
                    SkipStream(s, MimeTypes.getTrackType(mime))
                }
            }
            streams[i] = next[i]
        }
        wrappers = next
        abandon("tracks reselected")
        return pos
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        inner.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = inner.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long {
        abandon("seek")
        return inner.seekToUs(positionUs)
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        inner.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = inner.isLoading

    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)

    // ---- MediaPeriod.Callback ----

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }
}
