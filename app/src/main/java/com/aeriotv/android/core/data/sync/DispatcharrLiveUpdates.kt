package com.aeriotv.android.core.data.sync

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.data.ChannelListDiff
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrLiveConnection
import com.aeriotv.android.core.network.DispatcharrLiveEligibility
import com.aeriotv.android.core.network.DispatcharrLiveEvent
import com.aeriotv.android.core.playback.PlaybackActivityTracker
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a Dispatcharr playlist in step with its server while the app is open:
 * a finished playlist refresh that changed channels reloads the lineup, a
 * finished EPG import refreshes the guide. Driven by [DispatcharrLiveConnection];
 * results reach the screen through [PlaylistRepository.cacheUpdates].
 *
 * Runs only while the setting is on, the active playlist is eligible
 * ([DispatcharrLiveEligibility]) and the app is in the foreground. Channel
 * edits made by hand in Dispatcharr send no event, so the lineup is also
 * compared every [CHANNEL_CHECK_INTERVAL_MS] while connected.
 *
 * Load: channel reloads are debounced, EPG events (dozens of sources refresh
 * throughout the day) are coalesced into one quiet sweep with a cooldown, and
 * nothing runs while multiview is up.
 */
@Singleton
class DispatcharrLiveUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val repository: PlaylistRepository,
    private val connection: DispatcharrLiveConnection,
) {
    private var started = false

    /** What identifies a connection; other row changes (counts, timestamps) must not reconnect. */
    private data class Target(val playlist: PlaylistEntity) {
        override fun equals(other: Any?): Boolean {
            val o = (other as? Target)?.playlist ?: return false
            val p = playlist
            return p.id == o.id && p.urlString == o.urlString && p.lanUrlString == o.lanUrlString &&
                p.username == o.username && p.password == o.password
        }
        override fun hashCode(): Int = playlist.id.hashCode()
    }

    /** Idempotent; called once from Application.onCreate. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            combine(prefs.dispatcharrLiveUpdates, repository.observeActivePlaylist(), foreground()) { on, playlist, fg ->
                val eligible = DispatcharrLiveEligibility.of(playlist) == DispatcharrLiveEligibility.AVAILABLE
                if (on && fg && eligible && playlist != null) Target(playlist) else null
            }
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target == null) {
                        Log.i(TAG, "live updates idle")
                        return@collectLatest
                    }
                    Log.i(TAG, "live updates on for ${target.playlist.id.take(8)}")
                    try {
                        follow(target.playlist)
                    } finally {
                        Log.i(TAG, "live updates stopped for ${target.playlist.id.take(8)}")
                    }
                }
        }
    }

    /** Socket, event handling and the hand-edit check, until cancelled. */
    private suspend fun follow(playlist: PlaylistEntity): Unit = coroutineScope {
        launch { connection.run(playlist) { repository.effectiveBaseUrl(playlist) } }

        var channelJob: Job? = null
        fun scheduleChannelCheck(reason: String, afterMs: Long) {
            channelJob?.cancel()
            channelJob = launch {
                delay(afterMs)
                checkChannels(playlist.id, reason)
            }
        }

        var epgJob: Job? = null
        var lastSweepAt = 0L
        fun scheduleEpgSweep(reason: String) {
            // Coalesce: a burst of source imports becomes one sweep.
            if (epgJob?.isActive == true) return
            epgJob = launch {
                val sinceLast = System.currentTimeMillis() - lastSweepAt
                delay(maxOf(EPG_DEBOUNCE_MS, EPG_COOLDOWN_MS - sinceLast))
                awaitNoMultiview()
                lastSweepAt = System.currentTimeMillis()
                Log.i(TAG, "EPG sweep ($reason)")
                // Gated server-side by the sources fingerprint; announces
                // CacheUpdate.Guide when it refreshed anything.
                repository.startEpgBackgroundSweep(playlist.id)
            }
        }

        // Hand edits in Dispatcharr send no event: compare the lineup regularly.
        launch {
            while (true) {
                delay(CHANNEL_CHECK_INTERVAL_MS)
                if (channelJob?.isActive != true) scheduleChannelCheck("periodic", 0)
            }
        }

        connection.events.collect { event ->
            when (event) {
                // Also after every reconnect: catch up on anything missed while
                // disconnected. Both checks are cheap when nothing changed.
                DispatcharrLiveEvent.Connected -> {
                    scheduleChannelCheck("connected", CONNECT_SETTLE_MS)
                    scheduleEpgSweep("connected")
                }
                is DispatcharrLiveEvent.PlaylistRefreshed ->
                    if (event.channelsChanged) scheduleChannelCheck("playlist ${event.accountId} refreshed", CHANNEL_DEBOUNCE_MS)
                    else Log.i(TAG, "playlist ${event.accountId} refreshed without channel changes")
                is DispatcharrLiveEvent.EpgRefreshed -> scheduleEpgSweep("EPG source ${event.sourceId} refreshed")
                DispatcharrLiveEvent.RecordingsChanged -> Unit
            }
        }
    }

    /**
     * Re-fetch the lineup and hand it to the open app when it changed. New
     * channels get the live guide window straight away (a non-authoritative
     * merge, so nothing cached is deleted); the rest follows from the sweep.
     */
    private suspend fun checkChannels(playlistId: String, reason: String) {
        awaitNoMultiview()
        val playlist = repository.activePlaylist()?.takeIf { it.id == playlistId } ?: return
        val before = repository.loadCachedChannels(playlistId)
        val result = withContext(Dispatchers.IO) { repository.refresh(playlist) }
        val channels = result.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "channel check ($reason) failed: ${it::class.simpleName}: ${it.message}")
            return
        }
        val diff = ChannelListDiff.between(before, channels)
        Log.i(TAG, "channel check ($reason): $diff")
        if (!diff.hasChanges) return
        repository.announceCacheUpdate(PlaylistRepository.CacheUpdate.Channels(playlistId, channels))
        if (diff.added > 0) {
            val now = System.currentTimeMillis()
            val merged = runCatching {
                repository.fetchDispatcharrGridRange(playlist, now - HOUR_MS, now + DAY_MS)
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "guide window for new channels failed: ${it.message}")
                0
            }
            if (merged > 0) repository.announceCacheUpdate(PlaylistRepository.CacheUpdate.Guide(playlistId))
        }
    }

    /** Multiview owns the decoders; server-triggered work waits until it closes. */
    private suspend fun awaitNoMultiview() {
        while (PlaybackActivityTracker.isMultiStreamActive) delay(MULTIVIEW_POLL_MS)
    }

    /**
     * Foreground, with a grace period before reporting background: some TV
     * builds deliver a stale ON_STOP right after a relaunch (see
     * CompanionHostController), and a short trip to the system settings
     * should not drop and rebuild the socket.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun foreground(): Flow<Boolean> =
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .transformLatest { fg ->
                if (fg) {
                    emit(true)
                } else {
                    delay(BACKGROUND_GRACE_MS)
                    emit(isProcessForeground())
                }
            }
            .distinctUntilChanged()

    private fun isProcessForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val pid = android.os.Process.myPid()
        return am.runningAppProcesses?.any {
            it.pid == pid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private companion object {
        const val TAG = "DispatcharrLive"
        const val HOUR_MS = 60L * 60_000L
        const val DAY_MS = 24L * HOUR_MS
        const val BACKGROUND_GRACE_MS = 30_000L
        /** Let the server finish writing channels before re-reading them. */
        const val CHANNEL_DEBOUNCE_MS = 5_000L
        const val CONNECT_SETTLE_MS = 10_000L
        const val CHANNEL_CHECK_INTERVAL_MS = 10L * 60_000L
        const val EPG_DEBOUNCE_MS = 60_000L
        const val EPG_COOLDOWN_MS = 5L * 60_000L
        const val MULTIVIEW_POLL_MS = 60_000L
    }
}
