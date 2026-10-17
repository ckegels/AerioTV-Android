package com.aeriotv.android.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * The stream's own clock for watching together in step (WatchTogether): the PTS of the first
 * audio or video packet of a live connection. Media3's TS extractor counts the player's
 * position from that same first timestamp, so first PTS + position is the broadcast moment on
 * screen, and two devices fed the same bytes by one channel's buffer compare exactly.
 *
 * Reads along, changes nothing: the bytes pass through as they came, and after the first
 * timestamp (or [GIVE_UP_BYTES] without one) the probe stops looking.
 */
@UnstableApi
class LivePtsProbe(private val upstream: DataSource, private val onFirstPts: (Long) -> Unit) : DataSource {

    private val finder = FirstPtsFinder()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        finder.reset()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0 && !finder.done) finder.feed(buffer, offset, n)?.let(onFirstPts)
        return n
    }

    override fun getUri() = upstream.uri

    override fun getResponseHeaders() = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val onFirstPts: (Long) -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = LivePtsProbe(upstream.createDataSource(), onFirstPts)
    }

    companion object {
        const val GIVE_UP_BYTES = 4 * 1024 * 1024
    }
}

/** Finds the first PES timestamp (audio, video or private stream) in a TS byte stream. */
internal class FirstPtsFinder {
    private val pending = java.io.ByteArrayOutputStream()
    private var seen = 0

    /** Found, or given up. */
    var done = false
        private set

    fun reset() {
        pending.reset(); seen = 0; done = false
    }

    /** The first PTS (90 kHz) once these bytes complete it, else null. */
    fun feed(b: ByteArray, off: Int, len: Int): Long? {
        if (done) return null
        pending.write(b, off, len)
        seen += len
        val data = pending.toByteArray()
        val pts = scan(data)
        if (pts != null || seen > LivePtsProbe.GIVE_UP_BYTES) {
            done = true
            pending.reset()
        }
        return pts
    }

    private fun scan(d: ByteArray): Long? {
        // The first packet boundary: three sync bytes 188 apart
        var p = 0
        while (p + PACKET * 2 < d.size &&
            !(d[p] == SYNC && d[p + PACKET] == SYNC && d[p + PACKET * 2] == SYNC)
        ) p++
        while (p + PACKET <= d.size) {
            if (d[p] != SYNC) return null
            val unitStart = d[p + 1].toInt() and 0x40 != 0
            val adaptation = (d[p + 3].toInt() shr 4) and 0x3
            var payload = p + 4
            if (adaptation == 2 || adaptation == 3) payload += 1 + (d[p + 4].toInt() and 0xFF)
            if (unitStart && (adaptation == 1 || adaptation == 3) && payload + 14 <= p + PACKET &&
                d[payload] == 0.toByte() && d[payload + 1] == 0.toByte() && d[payload + 2] == 1.toByte()
            ) {
                val streamId = d[payload + 3].toInt() and 0xFF
                val media = streamId in 0xC0..0xEF || streamId == 0xBD
                val hasPts = d[payload + 7].toInt() and 0x80 != 0
                if (media && hasPts) return readTs(d, payload + 9)
            }
            p += PACKET
        }
        return null
    }

    companion object {
        const val PACKET = 188
        const val SYNC = 0x47.toByte()

        fun readTs(b: ByteArray, o: Int): Long =
            ((b[o].toLong() shr 1) and 0x07 shl 30) or
                ((b[o + 1].toLong() and 0xFF) shl 22) or
                (((b[o + 2].toLong() and 0xFF) shr 1) shl 15) or
                ((b[o + 3].toLong() and 0xFF) shl 7) or
                ((b[o + 4].toLong() and 0xFF) shr 1)
    }
}

/** Where two devices showing one channel are, from their stream timestamps. */
object InStep {
    private const val WRAP = 1L shl 33

    /**
     * How far ahead (ms, negative when behind) this device is of the other: [myPts] shown at
     * [myAtMs], [theirPts] at [theirAtMs] (both wall-clock ms), the other projected to this
     * device's moment at normal speed. PTS wraps every 2^33 ticks (26.5 h).
     */
    fun aheadMs(myPts: Long, myAtMs: Long, theirPts: Long, theirAtMs: Long): Long {
        val theirsNow = theirPts + (myAtMs - theirAtMs) * 90
        var d = Math.floorMod(myPts - theirsNow, WRAP)
        if (d > WRAP / 2) d -= WRAP
        return d / 90
    }

    /** The position on screen as a PTS: the first timestamp plus the player's position. */
    fun shown(firstPts: Long, positionMs: Long): Long = Math.floorMod(firstPts + positionMs * 90, WRAP)
}

/**
 * Whether a device behind the one it watches together with should catch up by tuning afresh.
 *
 * A fresh connection only takes away what a stall, a pause or a rewind added. What a device
 * itself adds -- a slower start, a deeper buffer before it plays -- stays: on a Pixel 9 against
 * a Shield (2026-10-17) that was about 5 s, every fresh tune landed 5 s behind again, and with
 * a 1.5 s slack the phone reconnected every 30 s, which looked like endless buffering. So:
 * only more than [SLACK_MS] behind (the user: 5 s apart is fine), at most once per [MIN_GAP_MS],
 * and when one catch-up did not take away at least [IMPROVE_MS], not again on this channel.
 */
class InStepCatchUp {
    private var channel: String? = null
    private var lastTuneAtMs = Long.MIN_VALUE / 2
    private var behindBeforeTune: Long? = null
    private var givenUp = false

    /** True: tune afresh now. [behindMs] is how far behind the other this device shows. */
    fun decide(channelId: String?, behindMs: Long, nowMs: Long): Boolean {
        if (channelId != channel) {
            channel = channelId
            lastTuneAtMs = Long.MIN_VALUE / 2
            behindBeforeTune = null
            givenUp = false
        }
        if (givenUp) return false
        behindBeforeTune?.let { before ->
            behindBeforeTune = null
            // The first look after a catch-up: no better, so it is this device's own delay
            if (behindMs > SLACK_MS && before - behindMs < IMPROVE_MS) {
                givenUp = true
                return false
            }
        }
        if (behindMs <= SLACK_MS || behindMs > MAX_MS) return false
        if (nowMs - lastTuneAtMs < MIN_GAP_MS) return false
        lastTuneAtMs = nowMs
        behindBeforeTune = behindMs
        return true
    }

    companion object {
        const val SLACK_MS = 6_000L
        const val IMPROVE_MS = 3_000L
        const val MIN_GAP_MS = 120_000L
        /** Further than this is not the same moment of one broadcast (a wrap, a restart). */
        const val MAX_MS = 15 * 60_000L
    }
}
