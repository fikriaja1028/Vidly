/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class UpdateWatchProgressUseCase @Inject constructor(
    private val repository: LibraryRepository,
    private val preferencesManager: PreferencesManager
) {
    suspend operator fun invoke(videoId: String, progressMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (preferencesManager.isIncognitoMode.first()) return

        val ratio = progressMs.toFloat() / durationMs
        
        // Threshold Logic:
        // 1. If watched less than 10 seconds, don't save progress (don't clutter history with misclicks)
        // 2. If watched more than 95%, mark as fully completed
        val finalProgress = when {
            progressMs < 10000 -> return 
            ratio > 0.95f -> durationMs
            else -> progressMs
        }

        repository.updateWatchProgress(videoId, finalProgress, durationMs)
    }
}
