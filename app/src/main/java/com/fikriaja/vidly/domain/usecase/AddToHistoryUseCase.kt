/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.HistoryEntity
import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class AddToHistoryUseCase @Inject constructor(
    private val repository: LibraryRepository,
    private val preferencesManager: PreferencesManager
) {
    suspend operator fun invoke(history: HistoryEntity) {
        if (preferencesManager.isIncognitoMode.first()) return
        repository.addToHistory(history)
    }
}
