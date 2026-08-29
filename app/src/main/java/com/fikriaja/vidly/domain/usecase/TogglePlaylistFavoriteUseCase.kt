/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.PlaylistFavoriteEntity
import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class TogglePlaylistFavoriteUseCase @Inject constructor(
    private val repository: LibraryRepository
) {
    suspend operator fun invoke(favorite: PlaylistFavoriteEntity) {
        val isFavorite = repository.isPlaylistFavorite(favorite.playlistId).first()
        if (isFavorite) {
            repository.removeFromPlaylistFavorites(favorite)
        } else {
            repository.addToPlaylistFavorites(favorite)
        }
    }
}
