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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow
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
    val defaultGroupToken by viewModel.defaultGroupToken.collectAsStateWithLifecycle()
    val defaultGroupOptions by viewModel.defaultGroupOptions.collectAsStateWithLifecycle()
    val phoneGroupSelector by viewModel.phoneGroupSelector.collectAsStateWithLifecycle(initialValue = "sidebar")
    val guideGroupSelector by viewModel.guideGroupSelector.collectAsStateWithLifecycle(initialValue = "pills")
    val showEpgBadges by viewModel.showEpgBadges(isTv).collectAsStateWithLifecycle(initialValue = true)
    val hiddenEpgBadges by viewModel.hiddenEpgBadges.collectAsStateWithLifecycle(initialValue = emptySet())
    val scaleLiveTV by viewModel.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)
    val palette by viewModel.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)

    var editingGroupSelector by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<ProgramCategory?>(null) }
    val menuGuard = rememberTvMenuGuard()

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Live TV", onBack = onBack)

        val vp = rememberViewport()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = if (vp.formMaxWidth != Dp.Unspecified) {
                    Modifier.widthIn(max = vp.formMaxWidth)
                } else {
                    Modifier
                },
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
                            title = "Rounded corners in Guide view",
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
                        header = "List view",
                        footer = "Which layout Live TV opens in. Automatic uses the List on " +
                            "phones and the Guide on TV and larger tablets. On phones you " +
                            "can still switch for the current session with the List / Guide " +
                            "button; on TV this setting is the only switch.",
                    ) {
                        SettingsToggleRow(
                            title = "Rounded corners in List view",
                            checked = roundedArtwork,
                            onCheckedChange = viewModel::setRoundedArtwork,
                        )
                        val current = defaultLiveTVView.lowercase()
                        DEFAULT_LIVE_TV_VIEW_OPTIONS.forEach { (value, label) ->
                            SettingsSelectionRow(
                                label = label,
                                selected = current == value,
                                onClick = { viewModel.setDefaultLiveTVView(value) },
                            )
                        }
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
                item("groups") {
                    SettingsSection(
                        header = "Groups",
                        footer = "The Live TV group the app opens on for this playlist. " +
                            "Recently Watched is the last 25 channels you played, newest " +
                            "first; picking it also shows it in Manage Groups. A group " +
                            "that later disappears from the playlist falls back " +
                            "automatically.",
                    ) {
                        val fixedTokens = listOf(
                            com.aeriotv.android.feature.playlist.PlaylistViewModel.ALL_GROUPS,
                            com.aeriotv.android.feature.playlist.PlaylistViewModel.FAVORITES_GROUP,
                            com.aeriotv.android.feature.playlist.PlaylistViewModel.RECENT_GROUP,
                        )
                        (fixedTokens + defaultGroupOptions).forEach { token ->
                            val isAll =
                                token == com.aeriotv.android.feature.playlist.PlaylistViewModel.ALL_GROUPS
                            SettingsSelectionRow(
                                label = com.aeriotv.android.feature.livetv.groupDisplayName(token),
                                // Nothing stored reads as All Channels, which is
                                // where a first launch lands.
                                selected = defaultGroupToken == token ||
                                    (isAll && defaultGroupToken.isBlank()),
                                onClick = { viewModel.setDefaultGroupToken(token) },
                            )
                        }
                    }
                }

                item("group-selection") {
                    if (isTv) {
                        // Moved here from Remote Control (Settings phase 1). Same
                        // row, same dialog, same persisted key.
                        SettingsSection(
                            header = "Group Selection",
                            footer = "How channel groups are picked in the guide. Top pills keep the group row above the grid; the sidebar menu hides that row and opens by holding Left in the grid (unless Left (Hold) is reassigned in Remote Control). Only one is active at a time.",
                        ) {
                            GroupSelectionRow(
                                slotName = "Group Selection",
                                valueName = if (guideGroupSelector == "sidebar") "Sidebar menu" else "Top group pills",
                                onClick = { editingGroupSelector = true },
                            )
                        }
                    } else {
                        SettingsSection(
                            header = "Group Selection",
                            footer = "How Live TV picks a channel group. Drawer opens a " +
                                "group list from the header button; Pills puts the groups " +
                                "in a strip across the header.",
                        ) {
                            SettingsSelectionRow(
                                label = "Drawer",
                                selected = phoneGroupSelector != "pills",
                                onClick = { viewModel.setPhoneGroupSelector("sidebar") },
                            )
                            SettingsSelectionRow(
                                label = "Pills",
                                selected = phoneGroupSelector == "pills",
                                onClick = { viewModel.setPhoneGroupSelector("pills") },
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
                            title = "Show program badges",
                            subtitle = "LIVE, NEW, and season/episode pills on the guide",
                            checked = showEpgBadges,
                            onCheckedChange = { viewModel.setShowEpgBadges(isTv, it) },
                        )
                        if (showEpgBadges) {
                            for (badge in listOf("NEW", "REPEAT", "LIVE", "PREMIERE", "FINALE")) {
                                SettingsToggleRow(
                                    title = "${badge.first()}${badge.drop(1).lowercase()} badge",
                                    checked = badge !in hiddenEpgBadges,
                                    onCheckedChange = { on ->
                                        viewModel.setBadgeHidden(badge, hidden = !on)
                                    },
                                )
                            }
                        }
                    }
                }

                // MARK: Display Scale
                settingsCard(
                    header = "Display Scale",
                    footer = if (listViewShown) {
                        "Independent scale for the Live TV List. 100% matches the default; 85-175% lets you trade density for readability (150%+ shows fewer, larger items - handy on a TV across the room). Changes apply live."
                    } else {
                        "Independent scale for Live TV. 100% matches the default; 85-175% lets you trade density for readability (150%+ shows fewer, larger items - handy on a TV across the room). Changes apply live."
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
                            color = Color(0xFFFB8C00),
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

    if (editingGroupSelector) {
        TvActionMenuDialog(
            title = "Group Selection",
            actions = listOf(
                TvMenuAction(
                    label = if (guideGroupSelector != "sidebar") "Top group pills  (current)" else "Top group pills",
                ) {
                    viewModel.setGuideGroupSelector("pills")
                    editingGroupSelector = false
                },
                TvMenuAction(
                    label = if (guideGroupSelector == "sidebar") "Sidebar menu  (current)" else "Sidebar menu",
                ) {
                    viewModel.setGuideGroupSelector("sidebar")
                    editingGroupSelector = false
                },
            ),
            onDismiss = { editingGroupSelector = false },
            guard = menuGuard,
        )
    }
}

/** Label + current value row, as Remote Control drew the Group Selection row
 *  before it moved here. Same shared container, no new chrome. */
@Composable
private fun GroupSelectionRow(
    slotName: String,
    valueName: String,
    onClick: () -> Unit,
) {
    SettingsRowContainer(onClick = onClick) {
        Text(
            text = slotName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (valueName.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.textAccent,
            )
        }
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
