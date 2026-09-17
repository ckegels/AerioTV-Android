package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.ui.TmdbAttribution
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.textfield.SecretRevealIconButton
import com.aeriotv.android.ui.textfield.rememberSecretRevealState
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.tvFormFieldInput

/**
 * Settings > Movies & TV Shows. Library refresh cadence, TMDB poster lookup,
 * and the Movies & Series display scale.
 *
 * Settings phase 1 regroup: the refresh cadence and Program Posters rows came
 * from App Behaviors, the scale row from Appearance. Keys, control types and
 * copy are carried over unchanged, including the TV-only gate on the refresh
 * cadence rows.
 */
@Composable
fun MoviesAndTvShowsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    val vodRefreshHours by viewModel.vodLibraryRefreshHours.collectAsStateWithLifecycle(initialValue = 24)
    val programPostersTmdb by viewModel.programPostersTmdbEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedTmdbKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle(initialValue = "")
    val tmdbKeyState by viewModel.tmdbKeyTestState.collectAsStateWithLifecycle()
    val scaleMovies by viewModel.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)

    TvKeyboardOnOkHost {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsDetailTopBar(title = "Movies & TV Shows", onBack = onBack)

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
                    // MARK: Refresh library
                    //
                    // TV-only today, exactly as it was on App Behaviors. The
                    // pref is global, so nothing changes for phones/tablets.
                    if (isTv) item("refresh-library") {
                        SettingsSection(header = "Refresh library") {
                            listOf(
                                0 to "Every Launch",
                                24 to "Daily",
                                168 to "Weekly",
                            ).forEach { (hours, label) ->
                                SettingsSelectionRow(
                                    label = label,
                                    selected = vodRefreshHours == hours,
                                    onClick = { viewModel.setVodLibraryRefreshHours(hours) },
                                )
                            }
                            Text(
                                text = "Live TV channels refresh on every launch. Movies and TV Shows open from the saved library and re-sweep the provider on this schedule. Pull down on either tab to refresh right away.",
                                style = MaterialTheme.typography.bodySmall.subtext(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // MARK: Posters
                    item("posters") {
                        SettingsSection(
                            header = "Posters",
                            footer = "Show posters in the Program Info panel and fill in missing artwork on On Demand detail screens, looked up on TMDB with your own free API key (themoviedb.org). Off by default. The key syncs across your devices via Google Drive (kept in your private app data).",
                        ) {
                            SettingsToggleRow(
                                title = "TMDB poster fallback",
                                subtitle = "When a poster is missing, look it up on TMDB. Needs the free API key below.",
                                checked = programPostersTmdb,
                                onCheckedChange = viewModel::setProgramPostersTmdbEnabled,
                            )
                            if (programPostersTmdb) {
                                var keyDraft by remember(savedTmdbKey) { mutableStateOf(savedTmdbKey) }
                                val keyReveal = rememberSecretRevealState()
                                TmdbAttribution(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                    long = false,
                                    isTv = isTv,
                                )
                                OutlinedTextField(
                                    value = keyDraft,
                                    onValueChange = {
                                        keyDraft = it
                                        viewModel.resetTmdbKeyTestState()
                                    },
                                    label = { Text("TMDB API key (v3) or read token (v4)") },
                                    singleLine = true,
                                    visualTransformation = keyReveal.transformation,
                                    trailingIcon = {
                                        SecretRevealIconButton(state = keyReveal, contentLabel = "key")
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .tvFormFieldInput(
                                            horizontalFocusEscape = true,
                                            okSuppressed = { keyReveal.controlFocused },
                                        ),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.testTmdbKey(keyDraft) },
                                        enabled = keyDraft.isNotBlank() &&
                                            tmdbKeyState != SettingsViewModel.TmdbKeyTestState.Testing,
                                        modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                                    ) { Text("Test") }
                                    TextButton(
                                        onClick = { viewModel.saveTmdbKey(keyDraft) },
                                        enabled = keyDraft.isNotBlank() || savedTmdbKey.isNotBlank(),
                                        modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                                    ) { Text("Save") }
                                    Spacer(Modifier.weight(1f))
                                    val (statusText, statusColor) = when (tmdbKeyState) {
                                        SettingsViewModel.TmdbKeyTestState.Testing ->
                                            "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
                                        SettingsViewModel.TmdbKeyTestState.Valid ->
                                            "Valid key" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                        SettingsViewModel.TmdbKeyTestState.Invalid ->
                                            "Invalid key" to MaterialTheme.colorScheme.error
                                        SettingsViewModel.TmdbKeyTestState.Saved ->
                                            "Saved" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                        SettingsViewModel.TmdbKeyTestState.Idle ->
                                            "" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    if (statusText.isNotEmpty()) {
                                        Text(
                                            statusText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = statusColor,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // MARK: Display Scale
                    settingsCard(
                        header = "Display Scale",
                        footer = "Independent scale for Movies & Series. 100% matches the default; 85-175% lets you trade density for readability (150%+ shows fewer, larger items - handy on a TV across the room). Changes apply live.",
                    ) {
                        ScaleSliderRow(
                            label = "Movies & Series",
                            value = scaleMovies,
                            onValueChange = viewModel::setDisplayScaleMovies,
                        )
                    }
                }
            }
        }
    }
}
