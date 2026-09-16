package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.playback.NATIVE_HLS_QUERY
import com.aeriotv.android.core.playback.withNativeHls
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Behavior probe for Dispatcharr's native HLS live output.
 *
 * A supporting server answers the normal live URL plus `?output_format=hls`
 * with a 302 whose Location is a per-client media playlist
 * (`/proxy/hls/<uuid>/client_<id>/index.m3u8`). A server without the feature
 * answers the app's HTML page, a 404, or anything else that is not that
 * redirect. NOTHING is gated on the reported server version: Logan's test
 * server reports 0.28.2 because the HLS branch is behind current Dispatcharr.
 *
 * CONNECTION COST -- READ BEFORE CHANGING THIS.
 * Every request to `?output_format=hls` mints a NEW server-side client, and
 * the branch exposes no endpoint for releasing one, so this probe deliberately:
 *
 *  - does NOT follow the redirect (the 302 alone is the whole answer; walking
 *    to the playlist would start pulling segments for a client nobody plays),
 *  - tries HEAD first, which a well-behaved server can answer from the route
 *    without allocating anything, and only falls back to GET when HEAD is
 *    refused (405/501), and
 *  - runs ONLY on the capability cadence (cold launch, foreground after a
 *    minute, manual refresh, six-hour TTL) -- never on the tune path, which
 *    reads the cached verdict and issues exactly one request.
 *
 * OPEN QUESTION FOR THE DISPATCHARR SIDE: whether a minted client that never
 * fetches its playlist is reaped, how long that takes, and whether it counts
 * against `user.stream_limit` in the meantime. If it does count, this probe
 * needs either a release endpoint or a capability field on an existing API
 * response (for example `/api/core/settings/`) to replace it.
 */
object NativeHlsProbe {

    private const val TAG = "AerioCaps"

    /** Path marker every per-client HLS playlist Location carries. */
    private const val HLS_PLAYLIST_MARKER = "/proxy/hls/"

    /** Deliberately short: a probe must never delay a launch. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            // The 302 IS the answer. Following it would mint a client AND then
            // fetch a playlist we are never going to play.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Supported / not supported / could not tell (null). */
    suspend fun probe(
        liveStreamUrl: String,
        headers: Map<String, String>,
    ): Boolean? = withContext(Dispatchers.IO) {
        val url = withNativeHls(liveStreamUrl)
        if (!url.contains(NATIVE_HLS_QUERY)) return@withContext null
        val head = attempt(url, headers, head = true)
        // 405 / 501: the server refuses HEAD on the proxy route. Fall back to a
        // GET whose body we never read, still without following the redirect.
        val result = if (head is Outcome.MethodRefused) attempt(url, headers, head = false) else head
        when (result) {
            is Outcome.Supported -> {
                Log.i(TAG, "native-hls probe: supported (302 -> ${result.location})")
                true
            }
            is Outcome.Unsupported -> {
                Log.i(TAG, "native-hls probe: unsupported (${result.detail})")
                false
            }
            else -> {
                Log.w(TAG, "native-hls probe: inconclusive")
                null
            }
        }
    }

    private sealed interface Outcome {
        data class Supported(val location: String) : Outcome
        data class Unsupported(val detail: String) : Outcome
        data object MethodRefused : Outcome
        data object Inconclusive : Outcome
    }

    private fun attempt(url: String, headers: Map<String, String>, head: Boolean): Outcome {
        val builder = Request.Builder().url(url)
        // LAN needs no auth on this path, but we keep sending our normal
        // headers so a WAN / reverse-proxied server answers the same way.
        headers.forEach { (k, v) ->
            if (k.isNotEmpty() && k.all { c -> c.code in 0x21..0x7e }) builder.addHeader(k, v)
        }
        if (head) builder.head()
        val request = builder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 405 || code == 501) return methodRefusal(head)
                val location = response.header("Location").orEmpty()
                when {
                    code !in 300..399 -> Outcome.Unsupported("HTTP $code, no redirect")
                    location.isBlank() -> Outcome.Unsupported("HTTP $code with no Location")
                    !location.contains(HLS_PLAYLIST_MARKER, ignoreCase = true) ->
                        Outcome.Unsupported("redirect to $location")
                    !location.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                        Outcome.Unsupported("redirect to $location")
                    else -> Outcome.Supported(location)
                }
            }
        } catch (t: Throwable) {
            // Transport failure proves nothing about the feature.
            Log.w(TAG, "native-hls probe transport failure: ${t.message}")
            Outcome.Inconclusive
        }
    }

    /** A HEAD refusal is retryable as GET; a GET refusal is a real answer. */
    private fun methodRefusal(head: Boolean): Outcome =
        if (head) Outcome.MethodRefused else Outcome.Unsupported("method not allowed")
}
