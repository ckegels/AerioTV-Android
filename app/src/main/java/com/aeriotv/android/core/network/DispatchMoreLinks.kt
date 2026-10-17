package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watch together (Dispatch More v260, its app_links): arrTV devices of one login join each
 * other, and from then on whichever changes channel, the others follow. Both are always on
 * the same channel, one provider connection, so it also fits logins held to one device at a
 * time. Live channels only.
 *
 * What the screens share: whether this device is linked, the channel it was asked to follow
 * (by the server's socket message, or by joining), and which button of the other-device
 * notice the remote is on.
 */
object WatchTogether {
    /** A channel this device is to tune to, by its Dispatcharr uuid. [seq] tells two
     *  requests for the same channel apart. */
    data class Follow(val channelUuid: String, val seq: Long = System.nanoTime())

    private val _follow = MutableStateFlow<Follow?>(null)

    /** The channel to tune to, until a screen has (see [consumed]). */
    val follow: StateFlow<Follow?> = _follow.asStateFlow()

    private val _linked = MutableStateFlow(false)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    /** The full player is open: it tunes a follow in place; otherwise the app opens it. */
    @Volatile
    var playerOpen = false

    /** The offer to join a device watching is asked once per start of the app. */
    @Volatile
    var offerAsked = false

    /** The other-device notice on a TV: 0 = Watch here, 1 = Join (Left / Right move it). */
    val noticeChoice = MutableStateFlow(0)

    /** Installed by Navigation for the life of the app: join a device / stop watching
     *  together (DispatchMoreLinks), so screens without the helper can ask. */
    @Volatile
    var joiner: ((String) -> Unit)? = null

    @Volatile
    var leaver: (() -> Unit)? = null

    fun join(device: String) {
        joiner?.invoke(device)
    }

    fun leave() {
        leaver?.invoke()
    }

    fun followChannel(channelUuid: String) {
        Log.i(TAG, "follow channel $channelUuid")
        _follow.value = Follow(channelUuid)
    }

    fun consumed(request: Follow) {
        _follow.compareAndSet(request, null)
    }

    internal fun setLinked(linked: Boolean) {
        _linked.value = linked
    }

    private const val TAG = "WatchTogether"
}

@Singleton
class DispatchMoreLinks @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val dao: PlaylistDao,
    private val repository: PlaylistRepository,
    private val client: DispatcharrClient,
    private val auth: DispatcharrAuthBroker,
) {
    /** Joins and leaves outlive the press (the notice or the menu closes at once). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // For the screens without this helper (the other-device notice, the Options menu)
        WatchTogether.joiner = { device ->
            joinInBackground(device) {
                android.widget.Toast.makeText(
                    context,
                    com.aeriotv.android.core.locale.AppText.get(com.aeriotv.android.R.string.player_join_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        WatchTogether.leaver = { leaveInBackground() }
    }

    /** The active playlist's server, when it is a Dispatch More server that offers it. */
    private suspend fun target(): Triple<String, String, String>? {
        val playlist = dao.firstActive()?.takeIf { !it.apiKey.isNullOrBlank() } ?: return null
        val base = runCatching { repository.effectiveBaseUrl(playlist) }.getOrNull() ?: return null
        val server = DispatchMore.serverFor(base)?.takeIf { it.linkDevices } ?: return null
        return Triple(playlist.id, base, server.linkPath)
    }

    fun available(): Boolean = DispatchMore.deviceId != null

    /** What this device may join now, and whether it is linked; null when the server says nothing. */
    suspend fun state(): DispatcharrClient.LinkState? = withContext(Dispatchers.IO) {
        val (playlistId, base, path) = target() ?: return@withContext null
        runCatching {
            auth.withApiKeyRetry(playlistId) { key -> client.fetchLinkState(base, key, path) }
        }.getOrNull()?.also { WatchTogether.setLinked(it.linked) }
    }

    /** Join [device]: links the two, and tunes to its channel (WatchTogether.follow). */
    suspend fun join(device: String): Boolean = withContext(Dispatchers.IO) {
        val (playlistId, base, path) = target() ?: return@withContext false
        val channel = runCatching {
            auth.withApiKeyRetry(playlistId) { key -> client.joinLink(base, key, path, device) }
        }.getOrNull() ?: return@withContext false
        Log.i(TAG, "joined $device on $channel")
        WatchTogether.setLinked(true)
        WatchTogether.followChannel(channel)
        true
    }

    fun joinInBackground(device: String, onFailed: () -> Unit = {}) {
        scope.launch {
            if (!join(device)) withContext(Dispatchers.Main) { onFailed() }
        }
    }

    /** Stop watching together (the Options menu, or a fresh start of the app). */
    fun leaveInBackground() {
        WatchTogether.setLinked(false)
        scope.launch {
            val (playlistId, base, path) = target() ?: return@launch
            runCatching { auth.withApiKeyRetry(playlistId) { key -> client.leaveLink(base, key, path) } }
                .onFailure { Log.w(TAG, "leave failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "WatchTogether"
    }
}
