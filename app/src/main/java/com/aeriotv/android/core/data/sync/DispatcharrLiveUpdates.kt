package com.aeriotv.android.core.data.sync

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.data.ChannelListDiff
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.EpgSweepGate
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
 * Load: channel reloads are debounced. EPG events (dozens of sources refresh
 * throughout the day) are coalesced into one window check (one request) with
 * a cooldown; the open app writes and repaints only the channels whose
 * schedule changed. While this runs, the stock full sweep waits while
 * something is being watched ([EpgSweepGate.holdWhileWatching]) and full
 * guide rebuilds wait for playback to stop. Nothing runs during multiview.
 */
@Singleton
class DispatcharrLiveUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val repository: PlaylistRepository,
    private val connection: DispatcharrLiveConnection,
    private val exoWindowState: com.aeriotv.android.feature.player.ExoWindowState,
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
        // Full screen vs corner player feeds PlaybackActivityTracker.watching.
        scope.launch {
            exoWindowState.mode.collect {
                PlaybackActivityTracker.fullscreenChanged(it == com.aeriotv.android.feature.player.ExoWindowState.Mode.Fullscreen)
            }
        }
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
    private suspend fun follow(playlist: PlaylistEntity): Unit = try {
        EpgSweepGate.holdWhileWatching = true
        followConnected(playlist)
    } finally {
        EpgSweepGate.holdWhileWatching = false
    }

    private suspend fun followConnected(playlist: PlaylistEntity): Unit = coroutineScope {
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
        var lastWindowAt = 0L
        fun scheduleGuideWindow(reason: String) {
            // Coalesce: a burst of source imports becomes one window check.
            if (epgJob?.isActive == true) return
            epgJob = launch {
                val sinceLast = System.currentTimeMillis() - lastWindowAt
                delay(maxOf(EPG_DEBOUNCE_MS, EPG_COOLDOWN_MS - sinceLast))
                awaitNoMultiview()
                lastWindowAt = System.currentTimeMillis()
                announceGuideWindow(playlist.id, reason)
            }
        }

        // Days past the live window are the stock sweep's job. A change owes
        // one; it runs as soon as nothing is watched full screen (right away
        // when the user is in the guide) instead of waiting for the next
        // 15-minute tick. The sweep is gated server-side by the sources
        // fingerprint, so an owed sweep with nothing new costs one request.
        var sweepOwed = false
        var lastSweepAt = 0L
        var sweepTimer: Job? = null
        // Runs the owed sweep when allowed: not watching full screen, and at
        // most once per SWEEP_MIN_INTERVAL_MS (sources refresh every few
        // minutes; days past tomorrow are not urgent). Otherwise it stays owed
        // and is retried when the interval ends or playback leaves full screen.
        fun tryOwedSweep(reason: String) {
            if (!sweepOwed || PlaybackActivityTracker.watching.value) return
            val wait = SWEEP_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastSweepAt)
            if (wait > 0) {
                if (sweepTimer?.isActive != true) sweepTimer = launch { delay(wait); tryOwedSweep(reason) }
                return
            }
            sweepOwed = false
            lastSweepAt = System.currentTimeMillis()
            runOwedSweep(playlist.id, reason)
        }
        fun owe(reason: String) {
            sweepOwed = true
            tryOwedSweep(reason)
        }
        launch {
            PlaybackActivityTracker.watching.collect { watching ->
                if (!watching) tryOwedSweep("playback left full screen")
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
                    scheduleGuideWindow("connected")
                    owe("connected")
                }
                is DispatcharrLiveEvent.PlaylistRefreshed ->
                    if (event.channelsChanged) scheduleChannelCheck("playlist ${event.accountId} refreshed", CHANNEL_DEBOUNCE_MS)
                    else Log.i(TAG, "playlist ${event.accountId} refreshed without channel changes")
                is DispatcharrLiveEvent.EpgRefreshed -> {
                    scheduleGuideWindow("EPG source ${event.sourceId} refreshed")
                    owe("EPG source ${event.sourceId} refreshed")
                }
                DispatcharrLiveEvent.RecordingsChanged -> Unit
            }
        }
    }

    /**
     * Re-fetch the lineup and hand it to the open app when it changed. New
     * channels have no guide on screen yet, so a window check follows.
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
        // New channels have no guide on screen yet: the window check fetches
        // and patches them like any other changed channel.
        if (diff.added > 0) announceGuideWindow(playlistId, "new channels")
    }

    /**
     * Fetch Dispatcharr's live window (now-1h..now+24h, one request) and hand
     * it to the open app, which writes and repaints only the channels whose
     * schedule changed. Days further ahead are left to the stock sweep, which
     * with live updates on runs while nothing is being watched.
     */
    private suspend fun announceGuideWindow(playlistId: String, reason: String) {
        val playlist = repository.activePlaylist()?.takeIf { it.id == playlistId } ?: return
        val now = System.currentTimeMillis()
        val from = now - HOUR_MS
        val to = now + DAY_MS
        val programmes = try {
            withContext(Dispatchers.IO) { repository.fetchLiveGridProgrammes(playlist, from, to) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "guide window ($reason) failed: ${t::class.simpleName}: ${t.message}")
            return
        }
        Log.i(TAG, "guide window ($reason): ${programmes.size} programmes fetched")
        if (programmes.isEmpty()) return
        repository.announceCacheUpdate(PlaylistRepository.CacheUpdate.GuideWindow(playlistId, programmes, from, to))
    }

    private fun runOwedSweep(playlistId: String, reason: String) {
        Log.i(TAG, "full EPG sweep ($reason)")
        // Starts in the repository's own scope; a sweep already running is reused.
        repository.startEpgBackgroundSweep(playlistId)
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
        /** The window check is one request plus a per-channel compare; cheap enough to follow closely. */
        const val EPG_COOLDOWN_MS = 2L * 60_000L
        const val MULTIVIEW_POLL_MS = 60_000L
        /** Owed full sweeps (13 days) run at most this often. */
        const val SWEEP_MIN_INTERVAL_MS = 10L * 60_000L
    }
}
