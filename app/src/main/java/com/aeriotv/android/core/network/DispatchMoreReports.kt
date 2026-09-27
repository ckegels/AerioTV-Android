package com.aeriotv.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.debug.LogSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.RandomAccessFile
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Send a report to the server" from the player (Dispatch More, contract
 * section 7). The app sends what it saw -- the channel, the player's state and
 * error, what it measured, the network and the tail of its own log -- and the
 * server adds its own view of that channel at that moment, so whoever reads
 * the report has both sides in one place instead of a screenshot and a guess
 * at the time.
 *
 * Offered only when the stream's server said on its capabilities call that it
 * takes reports, and only for a live channel it streams.
 */
@Singleton
class DispatchMoreReports @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaylistDao,
    private val client: DispatcharrClient,
    private val auth: DispatcharrAuthBroker,
    private val debugLogger: DebugLogger,
) {

    fun canReport(streamUrl: String?): Boolean =
        DispatchMore.serverFor(streamUrl)?.reports == true && DispatchMore.channelRef(streamUrl) != null

    /**
     * Send one report about [streamUrl]. [what] is the user's line (may be
     * blank), [happenedAtMs] when they asked to report, [player] the player's
     * side (AerioExoPlayerHolder.problemReportPlayer, read on the main thread).
     */
    suspend fun send(
        what: String,
        happenedAtMs: Long,
        streamUrl: String,
        player: Map<String, Any?>,
        extra: Map<String, Any?>,
    ): DispatcharrClient.ReportAnswer = withContext(Dispatchers.IO) {
        val server = DispatchMore.serverFor(streamUrl)
            ?: return@withContext DispatcharrClient.ReportAnswer.Refused("This server does not take reports")
        val base = DispatchMore.originOf(streamUrl)
            ?: return@withContext DispatcharrClient.ReportAnswer.Refused("Not a channel of this server")
        val playlist = dao.firstActive()?.takeIf { !it.apiKey.isNullOrBlank() }
            ?: return@withContext DispatcharrClient.ReportAnswer.Refused("No Dispatcharr login to send it with")
        val ref = DispatchMore.channelRef(streamUrl)
        val report = JsonObject(
            buildMap {
                DispatchMore.deviceId?.let { put("device_id", JsonPrimitive(it)) }
                if (DispatchMore.deviceName.isNotBlank()) put("device_name", JsonPrimitive(DispatchMore.deviceName))
                // The id in the form the stream was opened with.
                if (streamUrl.contains("/proxy/ts/stream/")) {
                    ref?.let { put("channel_uuid", JsonPrimitive(it)) }
                } else {
                    ref?.toIntOrNull()?.let { put("channel_id", JsonPrimitive(it)) }
                }
                put("what", JsonPrimitive(what.trim()))
                put("happened_at", JsonPrimitive(Instant.ofEpochMilli(happenedAtMs).toString()))
                put("app", toJson(appFacts()))
                put("player", toJson(player))
                put("network", toJson(networkFacts()))
                put("extra", toJson(extra))
                put("log", JsonPrimitive(logTail()))
            },
        )
        val answer = runCatching {
            auth.withApiKeyRetry(playlist.id) { key ->
                client.sendAppReport(base, key, server.reportPath, report)
            }
        }.getOrElse { DispatcharrClient.ReportAnswer.Refused(it.message ?: "Could not send the report") }
        Log.i(TAG, "problem report: $answer")
        answer
    }

    private fun appFacts(): Map<String, Any?> = mapOf(
        "name" to "arrTV",
        "version" to BuildConfig.VERSION_NAME,
        "build" to BuildConfig.VERSION_CODE,
        "android" to Build.VERSION.RELEASE,
        "model" to Build.MODEL,
        "manufacturer" to Build.MANUFACTURER,
    )

    private fun networkFacts(): Map<String, Any?> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching mapOf("type" to "none")
        mapOf(
            "type" to when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            },
            "vpn" to caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            "down_kbps" to caps.linkDownstreamBandwidthKbps,
        )
    }.getOrElse { emptyMap() }

    /**
     * The last minutes of the app's own log, newest last: the Debug Logging
     * file when it is on (already cleaned of credentials), otherwise this
     * process's lines still in logcat, cleaned the same way. The server keeps
     * the last [LOG_MAX] characters, so no more is sent.
     */
    private fun logTail(): String {
        val file = debugLogger.logFile()
        if (debugLogger.isEnabled() && file.length() > 0) {
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val from = (raf.length() - LOG_MAX).coerceAtLeast(0L)
                    raf.seek(from)
                    val bytes = ByteArray((raf.length() - from).toInt())
                    raf.readFully(bytes)
                    return String(bytes, Charsets.UTF_8).substringAfter('\n')
                }
            }
        }
        return runCatching {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "4000", "--pid=${android.os.Process.myPid()}"),
            )
            val text = proc.inputStream.bufferedReader().useLines { lines ->
                lines.map { LogSanitizer.redact(it) }.joinToString("\n")
            }
            runCatching { proc.destroy() }
            text.takeLast(LOG_MAX)
        }.getOrElse { "(no log: ${it.message})" }
    }

    companion object {
        private const val TAG = "DispatchMoreReports"
        private const val LOG_MAX = 100_000

        internal fun toJson(map: Map<String, Any?>): JsonObject = JsonObject(
            map.mapNotNull { (k, v) -> toJsonValue(v)?.let { k to it } }.toMap(),
        )

        private fun toJsonValue(v: Any?): JsonElement? = when (v) {
            null -> null
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Map<*, *> -> toJson(v.entries.associate { (k, x) -> k.toString() to x })
            is Iterable<*> -> kotlinx.serialization.json.JsonArray(v.map { toJsonValue(it) ?: JsonNull })
            else -> JsonPrimitive(v.toString())
        }
    }
}
