package com.aeriotv.android.core.playback

/**
 * Which Media3 pipeline a LIVE Dispatcharr tune uses.
 *
 * Dispatcharr's unreleased HLS branch answers the normal live stream URL with
 * `?output_format=hls` by redirecting (302) to a PER CLIENT media playlist:
 *
 *     GET  {base}/proxy/ts/stream/{uuid}?output_format=hls
 *     302  {base}/proxy/hls/{uuid}/client_{id}/index.m3u8
 *
 * A server WITHOUT the feature answers the app's own HTML page (or anything
 * that is not a 302 to a playlist), so the capability is detected by BEHAVIOR,
 * never by a version number: Logan's test server reports 0.28.2 because the
 * branch is behind current Dispatcharr.
 *
 * ONE TUNE == ONE CLIENT. Every request to the `?output_format=hls` URL mints a
 * new server-side client, so the app must issue exactly one. It does: the URL
 * is handed straight to [androidx.media3.exoplayer.hls.HlsMediaSource], whose
 * first (and only) request to it is the 302 that Media3's own redirect
 * following resolves. Media3 then parses the playlist against the RESOLVED URI
 * (ParsingLoadable uses `DataSource.getUri()`, which OkHttpDataSource reports
 * post-redirect) and DefaultHlsPlaylistTracker wraps that resolved URI as the
 * single variant, so every playlist reload and every segment GET afterwards
 * stays on the client that one request minted. There is no probe-then-open
 * double request on the tune path.
 *
 * RELEASING A CLIENT. The branch exposes NO release endpoint, and none is
 * invented here. Tearing the player down IS the release: AerioExoPlayerHolder's
 * `stop()` cancels the tracked OkHttp calls and clears the media items, so the
 * playlist reload loop and the segment GETs end and the server sees the client
 * go quiet. A channel change goes through the same path (the stale call
 * trackers are retired before the new source is primed), so one tune is one
 * client at a time.
 *
 * OPEN, NEEDS THE DISPATCHARR SIDE CONFIRMED:
 *  - how long a silent per-client HLS session lives before the server reaps it,
 *    and whether it counts against `user.stream_limit` while it lingers. If it
 *    does, a fast channel surf could trip the session-limit notice on a server
 *    where the TS path does not, and this needs a release endpoint or a
 *    capability field on an existing API response to replace the probe.
 *  - whether `change_stream` (Switch Stream) keeps the SAME per-client playlist
 *    and simply lists the new upstream's segments. The switch path assumes it
 *    does and therefore installs no keyframe skip; if the server instead ends
 *    the playlist, the switch will surface as a clean end plus reconnect.
 */
enum class LiveEngine {
    /** Today's path: ProgressiveMediaSource + TsExtractor on the raw TS proxy. */
    Ts,

    /** HlsMediaSource on `?output_format=hls`. */
    NativeHls,
}

/** Developer override, per playlist. [Auto] follows the detected capability. */
enum class LiveEngineOverride {
    Auto,
    ForceTs,
    ForceNativeHls,
    ;

    companion object {
        fun fromStored(value: String?): LiveEngineOverride = when (value) {
            "ts" -> ForceTs
            "hls" -> ForceNativeHls
            else -> Auto
        }
    }

    fun stored(): String = when (this) {
        Auto -> ""
        ForceTs -> "ts"
        ForceNativeHls -> "hls"
    }
}

/**
 * The live-engine decision for the ACTIVE playlist, published by
 * PlaylistRepository and read on the tune path by AerioExoPlayerHolder.
 *
 * Deliberately a tiny process-wide holder rather than an injected dependency:
 * the decision has to be readable from the synchronous middle of
 * `buildMediaSource`, and every writer (capability probe, playlist switch,
 * developer toggle) already runs inside the repository.
 *
 * UNKNOWN NEVER BLOCKS PLAYBACK. [supported] starts null and a null reads as
 * [LiveEngine.Ts]: a server we have not measured plays exactly as it does
 * today.
 */
object LiveEngineSelector {

    /** null = not measured yet (or a non-Dispatcharr source). */
    @Volatile
    var supported: Boolean? = null
        private set

    @Volatile
    var override: LiveEngineOverride = LiveEngineOverride.Auto
        private set

    fun publish(supported: Boolean?, override: LiveEngineOverride) {
        this.supported = supported
        this.override = override
    }

    /** Reset when the active playlist changes, before the new one is measured. */
    fun clear() {
        supported = null
        override = LiveEngineOverride.Auto
    }

    /** The engine a live tune should use right now. */
    fun engine(): LiveEngine = when (override) {
        LiveEngineOverride.ForceTs -> LiveEngine.Ts
        LiveEngineOverride.ForceNativeHls -> LiveEngine.NativeHls
        LiveEngineOverride.Auto -> if (supported == true) LiveEngine.NativeHls else LiveEngine.Ts
    }

    /** Human-readable reason for the [TUNE] line. */
    fun reason(): String = when (override) {
        LiveEngineOverride.ForceTs -> "forced"
        LiveEngineOverride.ForceNativeHls -> "forced"
        LiveEngineOverride.Auto -> when (supported) {
            true -> "detected"
            false -> "unsupported"
            null -> "unknown"
        }
    }
}

/** The query parameter Dispatcharr's HLS branch keys off. */
const val NATIVE_HLS_QUERY: String = "output_format=hls"

/** Path marker for a Dispatcharr live proxy stream. */
private const val LIVE_TS_MARKER = "/proxy/ts/stream/"

/**
 * True for the canonical Dispatcharr live URL, the only shape the native-HLS
 * query may be added to. Catch-up, timeshift, DVR, VOD and cast URLs all have
 * different shapes and are left alone.
 */
fun isDispatcharrLiveTsUrl(url: String): Boolean =
    url.contains(LIVE_TS_MARKER, ignoreCase = true) &&
        !url.contains(NATIVE_HLS_QUERY, ignoreCase = true)

/** True once [withNativeHls] (or the server) has put us on the HLS path. */
fun isNativeHlsUrl(url: String): Boolean =
    url.contains(NATIVE_HLS_QUERY, ignoreCase = true) ||
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

/**
 * Add `?output_format=hls` to a canonical live URL. Applied at the LAST moment
 * (inside the media-source build) and never persisted: the stored channel URL,
 * `lastPlayUrl`, StreamEndVerifier's uuid parse, CatchupUrlBuilder's base
 * extraction, the LAN/WAN rebuild and the cast ingest all keep seeing the
 * canonical `/proxy/ts/stream/<uuid>` form.
 */
fun withNativeHls(url: String): String {
    if (!isDispatcharrLiveTsUrl(url)) return url
    val separator = if (url.contains('?')) '&' else '?'
    return "$url$separator$NATIVE_HLS_QUERY"
}
