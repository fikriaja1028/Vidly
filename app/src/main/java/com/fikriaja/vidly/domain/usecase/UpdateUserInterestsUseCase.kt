/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.domain.recommendation.NeuroScoring
import com.fikriaja.vidly.domain.recommendation.NeuroTokenizer
import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class UpdateUserInterestsUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val preferencesManager: PreferencesManager
) {
    /**
     * Updates user interests based on video metadata.
     * @param text The title or uploader name to extract keywords from.
     * @param baseWeight The initial weight (e.g. 1.0 for title, 2.0 for uploader).
     * @param watchRatio The fraction of the video watched (0.0 to 1.0). 
     *                   If null, it's treated as a neutral interaction (1.0).
     */
    suspend operator fun invoke(text: String, baseWeight: Float = 1.0f, watchRatio: Float? = null) {
        if (preferencesManager.isRecommendationsPaused.first() || 
            preferencesManager.isIncognitoMode.first()) return

        val alpha = NeuroScoring.calculateLearningRate(watchRatio ?: 0.5f)
        val learningRate = 1f - alpha
        val finalWeight = baseWeight * learningRate
        
        val keywordVectors = NeuroTokenizer.tokenize(text)
        keywordVectors.forEach { (kw, frequency) ->
            libraryRepository.updateInterest(kw, finalWeight * frequency)
        }
        
        // FIX(BUG #11-related LOW): interest decay was previously triggered by
        // `System.currentTimeMillis() % 50 == 0L` â€” a millisecond coin-flip that
        // almost never fired. Apply decay on a proper interval instead: at most
        // once per 24h per process.
        val now = System.currentTimeMillis()
        val lastDecay = lastDecayTimestamp.get()
        if (now - lastDecay >= DECAY_INTERVAL_MS && lastDecayTimestamp.compareAndSet(lastDecay, now)) {
            libraryRepository.applyInterestDecay(0.95f)
        }
    }

    private companion object {
        // Decay at most once per day, process-wide (thread-safe).
        val lastDecayTimestamp = java.util.concurrent.atomic.AtomicLong(0L)
        const val DECAY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
