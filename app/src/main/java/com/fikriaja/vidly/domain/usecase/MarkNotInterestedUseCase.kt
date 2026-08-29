/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.BlacklistType
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.repository.LibraryRepository
import javax.inject.Inject

class MarkNotInterestedUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    /**
     * Marks a video as "Not Interested".
     * 1. Adds the video ID to the blacklist.
     * 2. Reduces the weight of keywords found in the title (negative feedback).
     */
    suspend operator fun invoke(video: VideoItem) {
        // 1. Blacklist the video
        libraryRepository.addToBlacklist(video.id, BlacklistType.VIDEO)
        
        // 2. Apply negative weights to keywords in the title to "unteach" the engine
        val keywords = extractKeywords(video.title)
        keywords.forEach { kw ->
            // Using a significant negative weight delta to suppress this topic
            libraryRepository.updateInterest(kw, -2.0f)
        }
        
        // Periodically purge low interests to keep the profile clean
        libraryRepository.applyInterestDecay(1.0f) 
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "with", "is", "are", "was", "were", "of",
            "video", "youtube", "play", "tube", "official", "latest", "best", "top"
        )
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length > 3 && it !in stopWords }
    }
}
