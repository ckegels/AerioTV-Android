package com.aeriotv.android.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsSubGroup
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.settingsCountSummary
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.dpadFocusWash
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.theme.textAccent

/**
 * Settings > Live TV. Everything that shapes the guide and the channel list:
 * presentation toggles, the List view choice, the TV guide layout, groups,
 * program badges, the Live TV display scale, and the category colors.
 *
 * Settings phase 1 regroup: the rows here came from App Behaviors (default
 * view, groups, guide layout, badges), Appearance (presentation, artwork,
 * display scale, category colors and palette) and, on TV, the Group Selection
 * row that used to sit under Remote Control. Every persisted key, control type
 * and string is carried over unchanged.
 */
@Composable
fun LiveTvSettingsScreen(
    onBack: () -> Unit,
    onOpenAddMoreCategories: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    // TV has no Live TV List view (Logan 2026-09-16, see core.ui.TvListView),
    // so the list-only options are hidden and the list-facing copy is written
    // for the Guide's channel column instead. The keys keep their values.
    val listViewShown = !isTv || com.aeriotv.android.core.ui.TvListView.ENABLED

    val showChannelLogos by viewModel.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNumbers by viewModel.showChannelNumbers.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNames by viewModel.showChannelNames.collectAsStateWithLifecycle(initialValue = true)
    val showProgramSubtitles by viewModel.showProgramSubtitles.collectAsStateWithLifecycle(initialValue = true)
    val roundedArtwork by viewModel.roundedArtwork.collectAsStateWithLifecycle(initialValue = true)
    val roundedArtworkGuide by viewModel.roundedArtworkGuide.collectAsStateWithLifecycle(initialValue = false)
    val defaultLiveTVView by viewModel.defaultLiveTVView.collectAsStateWithLifecycle(initialValue = "")
    val liveTvLayout by viewModel.liveTvLayout.collectAsStateWithLifecycle(initialValue = "basic")
    val phoneGroupSelector by viewModel.phoneGroupSelector.collectAsStateWithLifecycle(initialValue = "sidebar")
    val guideGroupSelector by viewModel.guideGroupSelector.collectAsStateWithLifecycle(initialValue = "pills")
    val showEpgBadges by viewModel.showEpgBadges(isTv).collectAsStateWithLifecycle(initialValue = true)
    val hiddenEpgBadges by viewModel.hiddenEpgBadges.collectAsStateWithLifecycle(initialValue = emptySet())
    val scaleLiveTV by viewModel.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)
    val palette by viewModel.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)

    var pickerTarget by remember { mutableStateOf<ProgramCategory?>(null) }

    SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Live TV", onBack = onBack)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.settingsFormWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = LocalTabBarBottomInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // MARK: Guide Presentation
                item("guide-presentation") {
                    SettingsSection(
                        header = "Guide Presentation",
                        footer = (
                            if (listViewShown) {
                                "Turn logos or numbers off to give long channel names more row width. Applies to the Live TV list and the Guide."
                            } else {
                                "Turn logos or numbers off to give long channel names more room in the Guide's channel column."
                            }
                            ) + " Rounded corners round channel logos and program artwork to match the card " +
                            "or cell they sit in. Artwork that floats on a transparent background stays square either way.",
                    ) {
                        SettingsToggleRow(
                            title = "Show Channel Logos",
                            subtitle = if (listViewShown) {
                                "Display each channel's logo in the Live TV list."
                            } else {
                                "Display each channel's logo in the Guide's channel column."
                            },
                            checked = showChannelLogos,
                            onCheckedChange = viewModel::setShowChannelLogos,
                        )
                        SettingsToggleRow(
                            title = "Show Channel Numbers",
                            subtitle = if (listViewShown) {
                                "Display each channel's number in the Live TV list and Guide."
                            } else {
                                "Display each channel's number in the Guide's channel column."
                            },
                            checked = showChannelNumbers,
                            onCheckedChange = viewModel::setShowChannelNumbers,
                        )
                        SettingsToggleRow(
                            title = "Show Channel Names",
                            subtitle = "Display each channel's name in the Guide's channel column.",
                            checked = showChannelNames,
                            onCheckedChange = viewModel::setShowChannelNames,
                        )
                        SettingsToggleRow(
                            title = "Show Program Subtitles",
                            subtitle = if (listViewShown) {
                                "Display the episode or match name under each program title in the Guide and Live TV list. Turn off if your EPG repeats the description there."
                            } else {
                                "Display the episode or match name under each program title in the Guide. Turn off if your EPG repeats the description there."
                            },
                            checked = showProgramSubtitles,
                            onCheckedChange = viewModel::setShowProgramSubtitles,
                        )
                        SettingsToggleRow(
                            title = "Rounded Corners in Guide View",
                            checked = roundedArtworkGuide,
                            onCheckedChange = viewModel::setRoundedArtworkGuide,
                        )
                    }
                }

                // MARK: List view
                //
                // Hidden on TV under the TvListView gate, exactly as the two
                // source screens gated these rows before the regroup.
                if (listViewShown) item("list-view") {
                    SettingsSection(
                        header = "List View",
                        footer = "Which layout Live TV opens in. Automatic uses the List on " +
                            "phones and the Guide on TV and larger tablets. On phones you " +
                            "can still switch for the current session with the List / Guide " +
                            "button; on TV this setting is the only switch.",
                    ) {
                        SettingsToggleRow(
                            title = "Rounded Corners in List View",
                            checked = roundedArtwork,
                            onCheckedChange = viewModel::setRoundedArtwork,
                        )
                        SettingsPickerRow(
                            title = "Default Live TV View",
                            inlineTitle = true,
                            options = DEFAULT_LIVE_TV_VIEW_OPTIONS.map {
                                SettingsPickerOption(it.first, it.second)
                            },
                            selected = defaultLiveTVView.lowercase(),
                            onSelect = viewModel::setDefaultLiveTVView,
                        )
                    }
                }

                // MARK: Guide Layout (TV)
                if (isTv) item("guide-layout") {
                    SettingsSection(header = "Guide Layout") {
                        SettingsSelectionRow(
                            label = "Basic",
                            subtitle = "Full program details in every guide cell",
                            selected = liveTvLayout != "preview",
                            onClick = { viewModel.setLiveTvLayout("basic") },
                        )
                        SettingsSelectionRow(
                            label = "Channel Preview",
                            subtitle = "A banner shows the highlighted program; cells keep the title and tags",
                            selected = liveTvLayout == "preview",
                            onClick = { viewModel.setLiveTvLayout("preview") },
                        )
                    }
                }

                // MARK: Groups
                //
                // The Default Group picker moved to Live TV's Manage Groups
                // sheet (Logan 2026-09-17, Apple parity): the group list, the
                // hide/show checks and the default all live in one place now.
                // Group Selection below is all this section carries.
                item("group-selection") {
                    // Phase 3: one picker on both inputs. TV used to open a
                    // TvActionMenuDialog from a value row and touch listed the
                    // two choices inline; the shared picker renders inline on
                    // TV and pushes a choice page on touch. Same keys.
                    if (isTv) {
                        SettingsSection(
                            header = "Group Selection",
                            footer = "How channel groups are picked in the guide. Top Group Pills keep the group row above the grid; Sidebar Menu hides that row and opens by holding Left in the grid (unless Left (Hold) is reassigned in Remote Control). Only one is active at a time.",
                        ) {
                            SettingsPickerRow(
                                title = "Group Selection",
                                options = listOf(
                                    SettingsPickerOption("pills", "Top Group Pills"),
                                    SettingsPickerOption("sidebar", "Sidebar Menu"),
                                ),
                                selected = if (guideGroupSelector == "sidebar") "sidebar" else "pills",
                                onSelect = viewModel::setGuideGroupSelector,
                            )
                        }
                    } else {
                        SettingsSection(
                            header = "Group Selection",
                            footer = "How Live TV picks a channel group. Sidebar Menu " +
                                "opens a group list from the header button, where a long " +
                                "press also reorders groups. Top Group Pills put the " +
                                "groups in a strip across the header. Long press a pill " +
                                "to set the default group.",
                        ) {
                            SettingsPickerRow(
                                title = "Group Selection",
                                options = listOf(
                                    SettingsPickerOption("sidebar", "Sidebar Menu"),
                                    SettingsPickerOption("pills", "Top Group Pills"),
                                ),
                                selected = if (phoneGroupSelector == "pills") "pills" else "sidebar",
                                onSelect = viewModel::setPhoneGroupSelector,
                            )
                        }
                    }
                }

                // MARK: Badges
                item("badges") {
                    SettingsSection(
                        header = "Badges",
                        footer = "Program badges are the LIVE, NEW, PREMIERE, FINALE, " +
                            "REPEAT, and season/episode pills on the guide and channel " +
                            "list. Remembered separately for " +
                            (if (isTv) "TVs" else "phones and tablets") +
                            " and synced across your " +
                            (if (isTv) "TVs" else "mobile devices") + ".",
                    ) {
                        SettingsToggleRow(
                            title = "Show Program Badges",
                            subtitle = "LIVE, NEW, and season/episode pills on the guide",
                            checked = showEpgBadges,
                            onCheckedChange = { viewModel.setShowEpgBadges(isTv, it) },
                        )
                        if (showEpgBadges) {
                            // Phase 3, item 4: the five per-badge toggles
                            // collapse under one master row whose subtitle says
                            // how many are on. TV keeps them inline.
                            val badges = listOf("NEW", "REPEAT", "LIVE", "PREMIERE", "FINALE")
                            val shown = badges.count { it !in hiddenEpgBadges }
                            SettingsSubGroup(
                                title = "Badge Types",
                                summary = settingsCountSummary(shown, badges.size),
                            ) {
                                badges.forEach { badge ->
                                    SettingsToggleRow(
                                        title = "${badge.first()}${badge.drop(1).lowercase()} Badge",
                                        checked = badge !in hiddenEpgBadges,
                                        onCheckedChange = { on ->
                                            viewModel.setBadgeHidden(badge, hidden = !on)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // MARK: Display Scale
                settingsCard(
                    header = "Display Scale",
                    footer = if (listViewShown) {
                        "Independent scale for the Live TV List. 100% matches the default; 85-150% lets you trade density for readability (larger steps show fewer, bigger items - handy on a TV across the room). Changes apply live."
                    } else {
                        "Independent scale for Live TV. 100% matches the default; 85-150% lets you trade density for readability (larger steps show fewer, bigger items - handy on a TV across the room). Changes apply live."
                    },
                ) {
                    ScaleSliderRow(
                        label = if (listViewShown) "Live TV List" else "Live TV",
                        value = scaleLiveTV,
                        onValueChange = viewModel::setDisplayScaleLiveTV,
                    )
                }

                // MARK: Colors
                settingsCard(
                    header = "Colors",
                    footer = "Tint EPG cells and channel cards by program category. Select a category below to override its hex.",
                ) {
                    ToggleRow(
                        title = "Color Programs by Category",
                        subtitle = "Apply category tints to the guide and channel rows.",
                        checked = palette.masterEnabled,
                        onCheckedChange = viewModel::setCategoryColorsEnabled,
                    )
                    DividerRow()
                    BoxWithConstraints {
                        val dim = if (palette.masterEnabled) 1f else 0.4f
                        if (twoUpInPane(maxWidth)) {
                            Column {
                                ProgramCategory.defaultBuckets.chunked(2)
                                    .forEachIndexed { rowIndex, pair ->
                                        if (rowIndex > 0) DividerRow()
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            pair.forEach { bucket ->
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .alpha(dim),
                                                ) {
                                                    CategoryPaletteRow(
                                                        bucket = bucket,
                                                        hex = palette.hexFor(bucket),
                                                        enabled = palette.masterEnabled,
                                                        onClick = { pickerTarget = bucket },
                                                    )
                                                }
                                            }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                            }
                        } else {
                            Column {
                                ProgramCategory.defaultBuckets.forEachIndexed { idx, bucket ->
                                    if (idx > 0) DividerRow()
                                    Box(modifier = Modifier.alpha(dim)) {
                                        CategoryPaletteRow(
                                            bucket = bucket,
                                            hex = palette.hexFor(bucket),
                                            enabled = palette.masterEnabled,
                                            onClick = { pickerTarget = bucket },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    DividerRow()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadFocusWash()
                            .clickable(enabled = palette.masterEnabled) { viewModel.resetCategoryPalette() }
                            .alpha(if (palette.masterEnabled) 1f else 0.4f)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Reset Colors to Defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            // Phase 3: the app's destructive/reset token (the
                            // same one Playlist Detail's Danger Zone and DVR's
                            // Reset to Default use), not a hardcoded orange.
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    DividerRow()
                    Box(modifier = Modifier.alpha(if (palette.masterEnabled) 1f else 0.4f)) {
                        AddMoreCategoriesRow(
                            extraOn = ProgramCategory.additionalBuckets.count { palette.isBucketEnabled(it) },
                            customCount = palette.custom.size,
                            enabled = palette.masterEnabled,
                            onClick = onOpenAddMoreCategories,
                        )
                    }
                }
            }
        }
    }

    }

    pickerTarget?.let { bucket ->
        HexPickerDialog(
            bucket = bucket,
            currentHex = palette.hexFor(bucket),
            onDismiss = { pickerTarget = null },
            onSave = { hex ->
                viewModel.setCategoryBucketHex(bucket, hex)
                pickerTarget = null
            },
            onReset = {
                viewModel.setCategoryBucketHex(bucket, null)
                pickerTarget = null
            },
        )
    }

}

/** Default Live TV View choices. Empty string = "Automatic" (form-factor
 *  default: List on compact phones, Guide on tablets / TV). "list" / "guide"
 *  are explicit overrides. Written to the same [defaultLiveTVView] pref the
 *  Live TV screen reads; the in-screen List / Guide button is session-only and
 *  never writes here. */
private val DEFAULT_LIVE_TV_VIEW_OPTIONS = listOf(
    "" to "Automatic",
    "list" to "List",
    "guide" to "Guide",
)
