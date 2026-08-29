/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.SubscriptionEntity
import com.fikriaja.vidly.domain.repository.LibraryRepository
import com.fikriaja.vidly.domain.repository.VideoRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SyncSubscriptionMetadataUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val videoRepository: VideoRepository
) {
    suspend operator fun invoke() {
        val subscriptions = libraryRepository.getSubscriptions().first()
        
        // Sync channels that have missing metadata or seem to have ID as names
        subscriptions.filter { 
            it.subscriberCount == null || 
            it.subscriberCount == 0L || 
            it.thumbnailUrl == null ||
            it.name.startsWith("UC")
        }.forEach { sub ->
            try {
                // Add a small delay to be "good citizens" and avoid throttling
                kotlinx.coroutines.delay(500)
                
                val details = videoRepository.getChannelDetails(sub.channelId)
                libraryRepository.subscribe(
                    SubscriptionEntity(
                        channelId = sub.channelId,
                        name = details.name,
                        thumbnailUrl = details.avatarUrl ?: sub.thumbnailUrl,
                        subscriberCount = details.subscriberCount
                    )
                )
            } catch (e: Exception) {
                // Skip failed syncs to avoid blocking others
                e.printStackTrace()
            }
        }
    }
}
