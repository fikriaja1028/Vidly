/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.repository.DownloadRepository
import javax.inject.Inject

class ResumeDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(videoId: String) {
        repository.resumeDownload(videoId)
    }
}
