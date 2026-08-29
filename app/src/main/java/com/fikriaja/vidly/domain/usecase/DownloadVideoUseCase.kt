/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.repository.DownloadRepository
import javax.inject.Inject

class DownloadVideoUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(
        videoId: String,
        url: String?,
        title: String,
        thumbnailUrl: String,
        uploaderName: String,
        quality: String?,
        format: String?,
        audioUrl: String? = null,
        playlistId: String? = null,
        playlistTitle: String? = null
    ) {
        repository.startDownload(videoId, url, title, thumbnailUrl, uploaderName, quality, format, audioUrl, playlistId, playlistTitle)
    }
}
