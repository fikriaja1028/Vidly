/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.repository

import com.fikriaja.vidly.data.local.DownloadEntity
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun getAllDownloads(): Flow<List<DownloadEntity>>
    suspend fun getDownloadByVideoId(videoId: String): DownloadEntity?
    suspend fun getDownloadByVideoIdResilient(videoId: String): DownloadEntity?
    suspend fun startDownload(
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
    )
    suspend fun cancelDownload(videoId: String)
    suspend fun pauseDownload(videoId: String)
    suspend fun resumeDownload(videoId: String)
    suspend fun pauseAllActiveDownloads()
    suspend fun resumeAllPausedDownloads()
    suspend fun deleteDownload(videoId: String)
    suspend fun clearAllDownloads()
    suspend fun saveToPublicStorage(videoId: String): Result<Unit>
}
