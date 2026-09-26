package com.aeriotv.android.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.update.UpdateManager
import com.aeriotv.android.core.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin facade over the flavor-bound [UpdateManager] for the update prompt
 * (UpdateGate) and the Settings App Updates screen. In the play flavor the
 * manager is a no-op with isEnabled=false, so every surface hides itself.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager,
    private val preferences: AppPreferences,
) : ViewModel() {

    val isEnabled: Boolean get() = manager.isEnabled
    val state: StateFlow<UpdateState> = manager.state

    /** What's New sequencing: the launch prompt waits until the current
     *  version's notes were seen/seeded so two sheets never stack. */
    val lastSeenWhatsNewVersion: Flow<String> = preferences.lastSeenWhatsNewVersion

    fun autoCheck() = viewModelScope.launch { manager.check(manual = false) }
    /** True while a manual check (Settings button) is talking to GitHub. */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun manualCheck() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                manager.check(manual = true)
            } finally {
                _checking.value = false
            }
        }
    }
    fun resumePending() = viewModelScope.launch { manager.resumePending() }
    fun download() = manager.startDownload()
    fun install() = manager.install()
    fun later() = manager.skipAvailableVersion()
    fun dismissError() = manager.dismissError()
    fun refreshInstallPermission() = manager.refreshInstallPermission()

    /** Automatic update checks (App Updates screen), off by default. */
    val autoCheck: Flow<Boolean> = preferences.updateAutoCheck
    fun setAutoCheck(value: Boolean) = viewModelScope.launch { preferences.setUpdateAutoCheck(value) }
}
