package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.main.AppTab
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.theme.textAccent

/**
 * Settings > General. How the app starts, how often it refreshes in the
 * background, and the network request budget.
 *
 * Settings phase 1 regroup: Startup came from App Behaviors (Default Tab,
 * Launch, Orientation), Refresh and Network from the retired Network page.
 * Keys, control types and copy are carried over unchanged.
 */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    val defaultTab by viewModel.defaultTab.collectAsStateWithLifecycle(initialValue = "")
    val skipLoadingScreen by viewModel.skipLoadingScreen.collectAsStateWithLifecycle(initialValue = false)
    val autoResumeLastChannel by viewModel.autoResumeLastChannel.collectAsStateWithLifecycle(initialValue = false)
    val backgroundRefreshEnabled by viewModel.backgroundRefreshEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val backgroundRefreshIntervalMins by viewModel.backgroundRefreshIntervalMins
        .collectAsStateWithLifecycle(initialValue = 360)
    val timeoutSecs by viewModel.networkTimeoutSecs.collectAsStateWithLifecycle(initialValue = 15.0)
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle(initialValue = 3)

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "General", onBack = onBack)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .adaptiveFormWidth()
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
                // MARK: Startup
                //
                // Apple phase 1 parity: Default Tab, the two launch toggles and
                // Auto-rotate are ONE section with one combined footer.
                SettingsSection(
                    header = "Startup",
                    footer = "The tab shown when the app first launches. Skip loading screen may cause brief stutter while data hydrates. Resume last channel re-opens the player on launch if the saved channel still exists in your playlist." +
                        if (isTv) {
                            ""
                        } else {
                            " The player's fullscreen button can still rotate into landscape whatever Auto-rotate says."
                        },
                ) {
                    // Search is a TV-only nav tab and not a sensible launch tab;
                    // on phones it does not exist at all. On Demand and Favorites
                    // are no longer tabs on any form factor (media center 2026-09-10).
                    AppTab.entries.filter {
                        it != AppTab.Search && it != AppTab.OnDemand && it != AppTab.Favorites
                    }.forEach { tab ->
                        val selected = (defaultTab.isEmpty() && tab == AppTab.LiveTV) ||
                            defaultTab == tab.name
                        SettingsSelectionRow(
                            label = tab.label,
                            selected = selected,
                            onClick = { viewModel.setDefaultTab(tab.name) },
                        )
                    }
                    SettingsToggleRow(
                        title = "Skip loading screen",
                        subtitle = "Land on Live TV instantly; data hydrates in the background",
                        checked = skipLoadingScreen,
                        onCheckedChange = viewModel::setSkipLoadingScreen,
                    )
                    SettingsToggleRow(
                        title = "Resume last channel",
                        subtitle = "Auto-start the last-played channel on launch.",
                        checked = autoResumeLastChannel,
                        onCheckedChange = viewModel::setAutoResumeLastChannel,
                    )
                    // Auto-Rotate (Logan 2026-08-07, iOS twin): phones/tablets
                    // only - TVs have no rotation. Default ON; when off
                    // MainActivity locks the activity to its current orientation.
                    if (!isTv) {
                        val autoRotate by viewModel.autoRotate
                            .collectAsStateWithLifecycle(initialValue = true)
                        SettingsToggleRow(
                            title = "Auto-rotate",
                            subtitle = "Follow the device orientation. When off, " +
                                "AerioTV stays in its current orientation",
                            checked = autoRotate,
                            onCheckedChange = viewModel::setAutoRotate,
                        )
                    }
                }

                // MARK: Refresh
                SettingsSection(
                    header = "Refresh",
                    footer = "Refresh channels + the EPG in the background on Wi-Fi while the battery isn't low, so the guide is current the moment you open the app. " +
                        if (isTv) {
                            "Off here means data refreshes only when you launch AerioTV or refresh from the playlist menu."
                        } else {
                            "Off here means data refreshes only when you launch AerioTV or pull to refresh."
                        },
                ) {
                    SettingsToggleRow(
                        title = "Refresh in the background",
                        checked = backgroundRefreshEnabled,
                        onCheckedChange = viewModel::setBackgroundRefreshEnabled,
                    )
                    if (backgroundRefreshEnabled) {
                        // iOS bgRefreshIntervalMins (audit P1 #7): how often the
                        // periodic refresh fires. Hidden when the master toggle is
                        // off so the UI doesn't suggest setting frequency on a
                        // disabled worker.
                        BG_REFRESH_INTERVAL_OPTIONS.forEach { opt ->
                            SettingsSelectionRow(
                                label = opt.label,
                                selected = opt.mins == backgroundRefreshIntervalMins,
                                onClick = { viewModel.setBackgroundRefreshIntervalMins(opt.mins) },
                            )
                        }
                    }
                }

                // MARK: Network
                //
                // tvOS Network (s_10) presents Request Timeout as a selection list
                // (5/10/15/30/60 seconds), not a slider: cleaner with a remote.
                SettingsSection(
                    header = "Network",
                    footer = "Adjust timeouts if you have a slow or unstable connection.",
                ) {
                    TIMEOUT_OPTIONS.forEach { secs ->
                        SettingsSelectionRow(
                            label = if (secs == 1) "1 second" else "$secs seconds",
                            selected = timeoutSecs.toInt() == secs,
                            onClick = { viewModel.setNetworkTimeoutSecs(secs.toDouble()) },
                        )
                    }
                }

                // Max Retries stays a stepper (no tvOS equivalent), on a resting card.
                SettingsSection(
                    header = "Max Retries",
                    footer = "Per-request retry budget (0-10).",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .settingsRowCard(focused = false)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Retries",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { if (maxRetries > 0) viewModel.setMaxRetries(maxRetries - 1) },
                            enabled = maxRetries > 0,
                            modifier = Modifier.dpadFocusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = maxRetries.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.textAccent,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = { if (maxRetries < 10) viewModel.setMaxRetries(maxRetries + 1) },
                            enabled = maxRetries < 10,
                            modifier = Modifier.dpadFocusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Increase")
                        }
                    }
                }
            }
        }
    }
}

/** tvOS Request Timeout options (s_10): 5/10/15/30/60 seconds. */
private val TIMEOUT_OPTIONS: List<Int> = listOf(5, 10, 15, 30, 60)

private data class BgRefreshIntervalOption(val mins: Int, val label: String)

/** iOS bgRefreshIntervalMins picker options. 360 (6h) is the default;
 *  match the iOS picker so synced preferences round-trip cleanly. */
private val BG_REFRESH_INTERVAL_OPTIONS: List<BgRefreshIntervalOption> = listOf(
    BgRefreshIntervalOption(60, "Every hour"),
    BgRefreshIntervalOption(180, "Every 3 hours"),
    BgRefreshIntervalOption(360, "Every 6 hours"),
    BgRefreshIntervalOption(720, "Every 12 hours"),
    BgRefreshIntervalOption(1440, "Every 24 hours"),
    BgRefreshIntervalOption(2880, "Every 48 hours"),
)
