/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.model.PlaylistDetails
import com.fikriaja.vidly.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class GetPlaylistDetailsUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(playlistUrl: String): Result<PlaylistDetails> {
        return try {
            Result.success(repository.getPlaylistDetails(playlistUrl))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
