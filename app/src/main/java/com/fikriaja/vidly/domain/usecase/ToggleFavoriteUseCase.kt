/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.data.local.FavoriteEntity
import com.fikriaja.vidly.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val repository: LibraryRepository
) {
    suspend operator fun invoke(favorite: FavoriteEntity) {
        val isFavorite = repository.isFavorite(favorite.videoId).first()
        if (isFavorite) {
            repository.removeFromFavorites(favorite)
        } else {
            repository.addToFavorites(favorite)
        }
    }
}
