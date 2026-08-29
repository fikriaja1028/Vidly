/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.model.ChannelDetails
import com.fikriaja.vidly.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class GetChannelDetailsUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(channelUrl: String): Result<ChannelDetails> {
        return try {
            Result.success(repository.getChannelDetails(channelUrl))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
