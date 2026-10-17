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
