/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.domain.repository.UpdateInfo
import com.fikriaja.vidly.domain.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val updateInfo: StateFlow<UpdateInfo> = updateRepository.updateInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateInfo())

    val isAutoUpdateEnabled: StateFlow<Boolean> = preferencesManager.isAutoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            isAutoUpdateEnabled.collectLatest { enabled ->
                if (enabled) {
                    updateRepository.checkForUpdates()
                }
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoUpdateEnabled(enabled)
            if (enabled) {
                updateRepository.checkForUpdates()
            }
        }
    }
}
