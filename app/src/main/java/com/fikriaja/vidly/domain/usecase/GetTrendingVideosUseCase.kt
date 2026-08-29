/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.model.PaginatedList
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.Page
import javax.inject.Inject

class GetTrendingVideosUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(): Result<PaginatedList<VideoItem>> {
        return try {
            // NewPipe usually fetches trending/kiosk as the initial page.
            // We'll need a way in Repository to fetch the initial trending page with its Page token.
            Result.success(repository.getTrendingVideos())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun fetchNextPage(page: Page): Result<PaginatedList<VideoItem>> {
        return try {
            Result.success(repository.fetchNextTrendingPage(page))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
