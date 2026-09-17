package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.preferences.PLAYER_EDGE_LEFT
import com.aeriotv.android.core.preferences.PLAYER_EDGE_RIGHT
import com.aeriotv.android.core.ui.SkipIntervals
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsSubGroup
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.settingsItemsSummary
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.ui.tv.dpadFocusEscape
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings > Player. Everything about fullscreen playback: the info card, live
 * rewind, playback buffering and recovery, gestures, multiview tiles, and the
 * TV display-mode controls.
 *
 * Settings phase 1 regroup: rows came from App Behaviors (info card, rewind,
 * skip intervals, gestures, stream recovery, audio, display), Network (buffer
 * size) and the retired Multiview page. Keys, control types and copy are
 * carried over unchanged.
 */
@Composable
fun PlayerSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()

    val cardChannelLogo by viewModel.playerCardShowChannelLogo
        .collectAsStateWithLifecycle(initialValue = true)
    val cardChannelName by viewModel.playerCardShowChannelName
        .collectAsStateWithLifecycle(initialValue = true)
    val cardProgramName by viewModel.playerCardShowProgramName
        .collectAsStateWithLifecycle(initialValue = true)
    val cardProgramTime by viewModel.playerCardShowProgramTime
        .collectAsStateWithLifecycle(initialValue = true)
    val cardProgramSubtitle by viewModel.playerCardShowProgramSubtitle
        .collectAsStateWithLifecycle(initialValue = true)
    val cardProgramDescription by viewModel.playerCardShowProgramDescription
        .collectAsStateWithLifecycle(initialValue = true)

    val liveRewindEnabled by viewModel.liveRewindEnabled.collectAsStateWithLifecycle(initialValue = false)
    val liveRewindDepth by viewModel.liveRewindDepthMinutes.collectAsStateWithLifecycle(initialValue = 30)
    val keepRecent by viewModel.liveRewindKeepRecent.collectAsStateWithLifecycle(initialValue = false)
    val keepCount by viewModel.liveRewindKeepCount.collectAsStateWithLifecycle(initialValue = 2)

    val skipBackSeconds by viewModel.skipBackSeconds
        .collectAsStateWithLifecycle(initialValue = SkipIntervals.DEFAULT_BACK_SECONDS)
    val skipForwardSeconds by viewModel.skipForwardSeconds
        .collectAsStateWithLifecycle(initialValue = SkipIntervals.DEFAULT_FORWARD_SECONDS)
    val bufferSize by viewModel.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val autoRecoverFrozenStreams by viewModel.autoRecoverFrozenStreams
        .collectAsStateWithLifecycle(initialValue = true)
    val audioPassthrough by viewModel.audioPassthroughEnabled.collectAsStateWithLifecycle(initialValue = false)

    val appleTVChannelFlip by viewModel.appleTVChannelFlip.collectAsStateWithLifecycle(initialValue = true)
    val playerBrightnessGesture by viewModel.playerBrightnessGesture.collectAsStateWithLifecycle(initialValue = false)
    val playerVolumeGesture by viewModel.playerVolumeGesture.collectAsStateWithLifecycle(initialValue = false)
    val playerBrightnessEdge by viewModel.playerBrightnessEdge
        .collectAsStateWithLifecycle(initialValue = PLAYER_EDGE_LEFT)

    val multiviewStyle by viewModel.multiviewAudioFocusStyle.collectAsStateWithLifecycle(initialValue = "centerIcon")
    val multiviewPadding by viewModel.multiviewTilePadding.collectAsStateWithLifecycle(initialValue = false)
    val multiviewRounded by viewModel.multiviewTileCornersRounded.collectAsStateWithLifecycle(initialValue = false)

    val startupRefreshRate by viewModel.startupRefreshRate.collectAsStateWithLifecycle(initialValue = "off")
    val matchContentResolution by viewModel.matchContentResolution.collectAsStateWithLifecycle(initialValue = false)

    SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Player", onBack = onBack)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .settingsFormWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalTabBarBottomInset.current,
                    ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // MARK: Info Card
                SettingsSection(
                    header = "Info Card",
                    footer = "Choose what appears on the program info card in the " +
                        "player while the controls are showing.",
                ) {
                    // Phase 3, item 4: six toggles behind one master row whose
                    // subtitle names what is on ("Logo, name, time" / "All 6").
                    // TV keeps them inline under the header.
                    val cardParts = listOf(
                        Triple("Channel Logo", "logo", cardChannelLogo) to
                            viewModel::setPlayerCardShowChannelLogo,
                        Triple("Channel Name", "name", cardChannelName) to
                            viewModel::setPlayerCardShowChannelName,
                        Triple("Program Name", "program", cardProgramName) to
                            viewModel::setPlayerCardShowProgramName,
                        Triple("Program Time", "time", cardProgramTime) to
                            viewModel::setPlayerCardShowProgramTime,
                        Triple("Program Subtitle", "subtitle", cardProgramSubtitle) to
                            viewModel::setPlayerCardShowProgramSubtitle,
                        Triple("Program Description", "description", cardProgramDescription) to
                            viewModel::setPlayerCardShowProgramDescription,
                    )
                    SettingsSubGroup(
                        title = "Info Card",
                        summary = settingsItemsSummary(
                            enabled = cardParts.filter { it.first.third }
                                .map { it.first.second }
                                .let { parts ->
                                    // Sentence-case the first item only, so the
                                    // subtitle reads "Logo, name, time".
                                    parts.mapIndexed { i, p ->
                                        if (i == 0) p.replaceFirstChar { c -> c.uppercase() } else p
                                    }
                                },
                            total = cardParts.size,
                        ),
                    ) {
                        cardParts.forEach { (part, setter) ->
                            SettingsToggleRow(
                                title = part.first,
                                checked = part.third,
                                onCheckedChange = setter,
                            )
                        }
                    }
                }

                // MARK: Live Rewind
                // Phase 3, item 4: Keep Available and Keep Recent Channels
                // were their own sections; they are sub-settings of the master
                // toggle, so they now sit under it in ONE card and still appear
                // only while it is on.
                SettingsSection(
                    header = "Live Rewind",
                    footer = buildString {
                        append(
                            "Buffers the channel you are watching so you can pause and " +
                                "rewind live TV. Uses device storage while you watch; " +
                                "buffered video is removed automatically.",
                        )
                        if (liveRewindEnabled) {
                            append(" ")
                            append(depthEstimateText(liveRewindDepth))
                            append(
                                if (keepRecent) {
                                    " Channels you flip away from keep buffering so you " +
                                        "can flip back and rewind across the time you were " +
                                        "away. Each kept channel uses an extra connection " +
                                        "to your provider; accounts limited to one " +
                                        "connection should leave this off."
                                } else {
                                    " Keep recent channels live buffers the channels you " +
                                        "most recently flipped away from, at one extra " +
                                        "provider connection per kept channel."
                                },
                            )
                        }
                    },
                ) {
                    SettingsToggleRow(
                        title = "Pause & rewind live TV",
                        subtitle = "Buffer fullscreen live playback on this device",
                        checked = liveRewindEnabled,
                        onCheckedChange = viewModel::setLiveRewindEnabled,
                    )
                    if (liveRewindEnabled) {
                        SettingsPickerRow(
                            title = "Rewind up to",
                            inlineTitle = true,
                            options = REWIND_DEPTH_MINUTES.map {
                                SettingsPickerOption(it, formatDepthMinutes(it))
                            },
                            selected = REWIND_DEPTH_MINUTES.minByOrNull {
                                abs(it - liveRewindDepth)
                            } ?: liveRewindDepth,
                            onSelect = viewModel::setLiveRewindDepthMinutes,
                        )
                        SettingsToggleRow(
                            title = "Keep recent channels live",
                            subtitle = "Buffer flipped-away channels in the background",
                            checked = keepRecent,
                            onCheckedChange = viewModel::setLiveRewindKeepRecent,
                        )
                        if (keepRecent) {
                            SteppedSliderRow(
                                label = "Channels to keep",
                                values = listOf(1, 2, 3, 4, 5),
                                selected = keepCount,
                                format = { it.toString() },
                                onSelect = viewModel::setLiveRewindKeepCount,
                            )
                        }
                    }
                }


                // MARK: Playback
                //
                // Apple phase 1 parity: skip intervals, buffer size and stream
                // recovery share one section.
                SettingsSection(
                    header = "Playback",
                    footer = (
                        if (isTv) {
                            "How far the skip buttons and a single left or right press move " +
                                "in live rewind, catch-up, recordings, movies, and TV shows. " +
                                "Holding left or right still scrubs faster the longer you hold."
                        } else {
                            "How far the skip buttons move in live rewind, catch-up, " +
                                "recordings, movies, and TV shows, including the cast remote " +
                                "and the playback notification."
                        }
                        ) + " Buffer Size controls how much stream data is pre-loaded: larger " +
                        "buffers reduce stuttering on poor connections but add startup delay. " +
                        "If a live stream stops sending video, Auto-Recover reloads it; turn " +
                        "that off if live channels restart or stutter during commercial " +
                        "breaks, and a brief freeze may show instead. Recovery applies to the " +
                        "next channel you tune.",
                ) {
                    SteppedSliderRow(
                        label = "Skip back",
                        values = SkipIntervals.CHOICES,
                        selected = skipBackSeconds,
                        format = ::formatSkipSeconds,
                        onSelect = viewModel::setSkipBackSeconds,
                    )
                    SteppedSliderRow(
                        label = "Skip forward",
                        values = SkipIntervals.CHOICES,
                        selected = skipForwardSeconds,
                        format = ::formatSkipSeconds,
                        onSelect = viewModel::setSkipForwardSeconds,
                    )
                    SettingsPickerRow(
                        title = "Buffer Size",
                        inlineTitle = true,
                        options = BUFFER_OPTIONS.map {
                            SettingsPickerOption(it.id, it.label, it.detail)
                        },
                        // Unknown ids (the retired "small") read as Default.
                        selected = BUFFER_OPTIONS.firstOrNull { it.id == bufferSize }?.id
                            ?: BUFFER_OPTIONS.first().id,
                        onSelect = viewModel::setStreamBufferSize,
                    )
                    SettingsToggleRow(
                        title = "Auto-Recover Frozen Streams",
                        subtitle = "Reload a live stream that stops sending video. Off keeps the stream as-is through commercial-break stutters.",
                        checked = autoRecoverFrozenStreams,
                        onCheckedChange = viewModel::setAutoRecoverFrozenStreams,
                    )
                }

                SettingsSection(
                    header = "Audio",
                    footer = "Passthrough sends surround sound audio as a bitstream for your TV or receiver to decode. Some TVs decode it late, which shows up as voices out of sync with lips on live TV. Off, AerioTV decodes audio itself and stays in sync. Takes effect on the next playback.",
                ) {
                    SettingsToggleRow(
                        title = "Surround sound passthrough",
                        subtitle = "Send AC-3 and E-AC-3 audio to your receiver untouched. Leave off if lip sync drifts.",
                        checked = audioPassthrough,
                        onCheckedChange = viewModel::setAudioPassthroughEnabled,
                    )
                }

                // MARK: Gestures
                SettingsSection(
                    header = "Gestures",
                    // tvOS / Android TV flip channels with D-pad up/down, not a
                    // swipe, so the "accidental swipes" caution is meaningless on
                    // a remote (user request: drop the note on TV). Phones keep it.
                    footer = if (isTv) {
                        null
                    } else {
                        "Turn off if accidental swipes during playback flip channels by mistake. " +
                            "Brightness and volume slides are recognized only inside a narrow band at the very edge of the screen, " +
                            "so they stay clear of the channel flip and of swiping down to minimize."
                    },
                ) {
                    SettingsToggleRow(
                        title = "Up / Down channel change",
                        subtitle = if (isTv) {
                            "While the player chrome is visible, press up for the next channel and down for the previous. Live single-stream playback only."
                        } else {
                            "While the player chrome is visible, swipe up for the next channel and down for the previous. Live single-stream playback only."
                        },
                        checked = appleTVChannelFlip,
                        onCheckedChange = viewModel::setAppleTVChannelFlip,
                    )
                    if (!isTv) {
                        SettingsToggleRow(
                            title = "Brightness edge slide",
                            subtitle = "Slide a finger up or down the brightness edge to dim or brighten the screen. Applies to this app only and is restored when you leave the player.",
                            checked = playerBrightnessGesture,
                            onCheckedChange = viewModel::setPlayerBrightnessGesture,
                        )
                        SettingsToggleRow(
                            title = "Volume edge slide",
                            subtitle = "Slide a finger up or down the other edge to change the media volume.",
                            checked = playerVolumeGesture,
                            onCheckedChange = viewModel::setPlayerVolumeGesture,
                        )
                        if (playerBrightnessGesture || playerVolumeGesture) {
                            listOf(
                                PLAYER_EDGE_LEFT to "Brightness: Left, Volume: Right",
                                PLAYER_EDGE_RIGHT to "Brightness: Right, Volume: Left",
                            ).forEach { (wire, label) ->
                                SettingsSelectionRow(
                                    label = label,
                                    selected = playerBrightnessEdge == wire,
                                    onClick = { viewModel.setPlayerBrightnessEdge(wire) },
                                )
                            }
                        }
                    }
                }

                // MARK: Multiview
                //
                // Apple phase 1 parity: indicator, spacing and tile corners
                // share one section. tvOS presents corners as a Square /
                // Rounded selection (s_09); same underlying Boolean.
                SettingsSection(
                    header = "Multiview",
                    footer = "How the grid shows which tile is unmuted. Center Icon fades with the chrome, Gray Outline stays visible, Accent Outline appears on switch and fades after 5 seconds. Padding inserts a small gap between tiles so each stream stands on its own.",
                ) {
                    SettingsPickerRow(
                        title = "Audio Focus Indicator",
                        inlineTitle = true,
                        options = AUDIO_FOCUS_OPTIONS.map {
                            SettingsPickerOption(it.id, it.label, it.detail)
                        },
                        selected = AUDIO_FOCUS_OPTIONS.firstOrNull { it.id == multiviewStyle }?.id
                            ?: AUDIO_FOCUS_OPTIONS.first().id,
                        onSelect = viewModel::setMultiviewAudioFocusStyle,
                    )
                    SettingsToggleRow(
                        title = "Padding Between Tiles",
                        subtitle = "Add a small gap between tiles for visual separation.",
                        checked = multiviewPadding,
                        onCheckedChange = viewModel::setMultiviewTilePadding,
                    )
                    SettingsPickerRow(
                        title = "Tile corners",
                        inlineTitle = true,
                        options = listOf(
                            SettingsPickerOption(false, "Square"),
                            SettingsPickerOption(true, "Rounded"),
                        ),
                        selected = multiviewRounded,
                        onSelect = viewModel::setMultiviewTileCornersRounded,
                    )
                }

                // MARK: Display (TV only)
                //
                // GH #38 + #40: each switch is a real HDMI mode change (brief
                // black screen), deliberate and user-opted.
                if (isTv) {
                    SettingsSection(
                        header = "Display",
                        footer = "Startup Refresh Rate switches the display once at app launch so it is already on your main content rate before the first channel (changes apply on next launch). Match Content Resolution outputs at the stream's resolution so your TV does the upscaling; the screen blinks briefly on each switch.",
                    ) {
                        SettingsToggleRow(
                            title = "Match content resolution",
                            subtitle = "Output 1080p streams at 1080p and let the TV upscale. Off keeps the display at its native mode.",
                            checked = matchContentResolution,
                            onCheckedChange = viewModel::setMatchContentResolution,
                        )
                        val rates = listOf(
                            SettingsPickerOption("off", "Off (system default)"),
                            SettingsPickerOption("50", "50 Hz"),
                            SettingsPickerOption("59.94", "59.94 Hz"),
                            SettingsPickerOption("60", "60 Hz"),
                        )
                        SettingsPickerRow(
                            title = "Startup Refresh Rate",
                            inlineTitle = true,
                            options = rates,
                            selected = rates.firstOrNull { it.value == startupRefreshRate }?.value
                                ?: "off",
                            onSelect = viewModel::setStartupRefreshRate,
                        )
                    }
                }
            }
        }
    }
    }
}

private data class AudioFocusOption(val id: String, val label: String, val detail: String)

private val AUDIO_FOCUS_OPTIONS: List<AudioFocusOption> = listOf(
    AudioFocusOption("centerIcon", "Center Icon", "Speaker icon centered on the active tile. Default."),
    AudioFocusOption("grayPersistent", "Gray Outline", "Subtle gray border always around the active tile."),
    AudioFocusOption("themeFading", "Accent Outline (Fading)", "Accent-tinted border that auto-hides after 5 seconds."),
)

data class BufferOption(val id: String, val label: String, val detail: String, val cachingMs: Int)

/**
 * Discord (di5cord20, Formuler Z11, 2026-08-09): "Default -> Small made
 * zapping worse." It cannot have. The live player applies
 * `minBufferMs = maxOf(4_000, bufferFloorMs)` (AerioExoPlayerHolder), and the
 * old ladder was 300 / 1000 / 3000 / 8000 ms, so **Small, Default and Large
 * all collapsed onto the same 4s/8s LoadControl**: three of the four options
 * did nothing at all, while their subtitles advertised latencies (300 ms,
 * 1 second, 3 seconds) the player never used. Extra Large was worse than
 * inert - 8000 produced min == max == 8s, a LoadControl with no headroom
 * between its two bounds.
 *
 * The 4s floor is not an accident: it is the measured fix for the recurring
 * mid-stream micro-stutter on the Streamer (see the LoadControl comment in
 * AerioExoPlayerHolder, which walks through why 500ms and 2.5s both failed).
 * Nothing may sit below it, which leaves no room for a real "Small" - so it
 * is gone, and anyone on it is mapped to Default, which is exactly what they
 * were already getting.
 *
 * Default keeps its current behaviour to the millisecond, so no existing
 * install changes. Large and Extra Large start doing what their labels always
 * claimed.
 */
internal val BUFFER_OPTIONS: List<BufferOption> = listOf(
    BufferOption("default", "Default", "4 seconds - recommended", 4_000),
    BufferOption("large", "Large", "8 seconds - unstable connections", 8_000),
    BufferOption("xlarge", "Extra Large", "16 seconds - very poor networks", 16_000),
)

/** Unknown ids (including the retired "small") resolve to Default. */
internal fun bufferMillisFor(id: String): Int =
    BUFFER_OPTIONS.firstOrNull { it.id == id }?.cachingMs ?: 4_000

/** Keep Available ladder (2026-07-11 rework, user directive round 2:
 *  the user-meaningful knob is HOW FAR BACK you can rewind, not how
 *  long files persist - retention is now a fixed internal 1 hour). */
private val REWIND_DEPTH_MINUTES = listOf(15, 30, 60, 90, 120, 180)

private fun formatDepthMinutes(mins: Int): String = when {
    mins < 60 -> "$mins minutes"
    mins == 60 -> "1 hour"
    mins % 60 == 0 -> "${mins / 60} hours"
    else -> "${mins / 60}h ${mins % 60}m"
}

/** Skip Intervals value readout: "10 seconds". */
private fun formatSkipSeconds(seconds: Int): String = "$seconds seconds"

/**
 * Storage estimate under the Keep Available slider: scales with the
 * depth choice (which bounds live disk usage now that retention is a
 * fixed short window) at typical stream bitrates - HD ~4 Mbps, FHD ~8,
 * UHD ~20 - so the user can pick what fits their streams and disk.
 */
private fun depthEstimateText(mins: Int): String {
    fun gb(mbps: Int): String {
        val v = mbps * 7.5 * mins / 1024.0
        return if (v < 10) String.format("~%.1f GB", v) else "~${v.roundToInt()} GB"
    }
    return "Uses up to ${gb(4)} in HD, ${gb(8)} in FHD, or ${gb(20)} in UHD while you watch."
}

/**
 * Discrete-stop slider row: label left, current value right, a stepped
 * Material slider beneath. TV-safe via [dpadFocusEscape] (the #90
 * lesson: UP/DOWN must move focus off the slider, LEFT/RIGHT adjust).
 */
@Composable
internal fun SteppedSliderRow(
    label: String,
    values: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    // Snap legacy/custom persisted values (e.g. 48h from the removed
    // custom dialog) to the nearest ladder stop for display; the pref
    // itself is only rewritten when the user moves the slider.
    val idx = values.indices.minByOrNull { abs(values[it] - selected) } ?: 0
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(values[idx]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.textAccent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = idx.toFloat(),
            onValueChange = { raw ->
                val newIdx = raw.roundToInt().coerceIn(0, values.lastIndex)
                if (values[newIdx] != selected) onSelect(values[newIdx])
            },
            valueRange = 0f..values.lastIndex.toFloat(),
            steps = (values.size - 2).coerceAtLeast(0),
            modifier = Modifier.dpadFocusEscape(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
