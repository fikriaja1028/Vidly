/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class IsPlaylistFavoriteUseCase @Inject constructor(
    private val repository: LibraryRepository
) {
    operator fun invoke(playlistId: String): Flow<Boolean> = repository.isPlaylistFavorite(playlistId)
}
