/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.fikriaja.vidly.data.local.*
import com.fikriaja.vidly.domain.repository.DownloadRepository
import com.fikriaja.vidly.services.VideoDownloadService
import com.fikriaja.vidly.utils.VidlyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val missionDao: MissionDao
) : DownloadRepository {

    private val workManager = WorkManager.getInstance(context)

    override fun getAllDownloads(): Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    override suspend fun getDownloadByVideoId(videoId: String): DownloadEntity? = 
        downloadDao.getDownloadById(videoId)

    override suspend fun getDownloadByVideoIdResilient(videoId: String): DownloadEntity? = withContext(Dispatchers.IO) {
        val entity = downloadDao.getDownloadById(videoId) ?: return@withContext null
        if (entity.status != DownloadStatus.COMPLETED) return@withContext entity

        val file = File(entity.filePath)
        if (file.exists()) return@withContext entity

        // Resilience: Try fallback extensions for existing broken playlist downloads
        val baseDir = context.getExternalFilesDir(null)
        val webmFile = File(baseDir, "$videoId.webm")
        val mp4File = File(baseDir, "$videoId.mp4")

        val fixedFile = when {
            webmFile.exists() -> webmFile
            mp4File.exists() -> mp4File
            else -> null
        }

        if (fixedFile != null) {
            val updated = entity.copy(filePath = fixedFile.absolutePath)
            downloadDao.updateDownload(updated)
            return@withContext updated
        }

        entity
    }

    override suspend fun startDownload(
        videoId: String,
        url: String?,
        title: String,
        thumbnailUrl: String,
        uploaderName: String,
        quality: String?,
        format: String?,
        audioUrl: String?,
        playlistId: String?,
        playlistTitle: String?
    ) {
        // FEATURE (Audio downloads): a mission with no video URL but an audio URL
        // is an audio-only download; pick a matching audio container extension.
        val isAudioOnly = url.isNullOrBlank() && !audioUrl.isNullOrBlank()
        val extension = when {
            isAudioOnly && (audioUrl?.contains("webm", ignoreCase = true) == true ||
                            audioUrl?.contains("opus", ignoreCase = true) == true) -> "opus"
            isAudioOnly -> "m4a"
            format?.contains("webm", ignoreCase = true) == true -> "webm"
            else -> "mp4"
        }
        val filePath = File(context.getExternalFilesDir(null), "$videoId.$extension").absolutePath
        val entity = DownloadEntity(
            videoId = videoId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            uploaderName = uploaderName,
            filePath = filePath,
            totalSize = 0,
            downloadedSize = 0,
            status = DownloadStatus.WAITING,
            quality = quality,
            format = format,
            videoUrl = url,
            audioUrl = audioUrl,
            playlistId = playlistId,
            playlistTitle = playlistTitle
        )
        downloadDao.insertDownload(entity)
        
        // New resilient logic
        val mission = DownloadMissionEntity(
            videoId = videoId,
            title = title,
            quality = quality ?: "Unknown",
            videoUrl = url,
            audioUrl = audioUrl,
            format = format,
            outputFilePath = filePath
        )
        val missionId = missionDao.insertMission(mission)
        
        startDownloadService(missionId)
    }

    private fun startDownloadService(missionId: Long) {
        val intent = Intent(context, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_START
            putExtra("missionId", missionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override suspend fun cancelDownload(videoId: String) {
        val mission = missionDao.getMissionByVideoId(videoId)
        mission?.let {
            val intent = Intent(context, VideoDownloadService::class.java).apply {
                action = VideoDownloadService.ACTION_STOP
                putExtra("missionId", it.id)
            }
            context.startService(intent)
            missionDao.deleteMission(it)
        }
        
        // FIX(BUG #12): removed workManager.cancelUniqueWork(videoId) â€” nothing ever
        // enqueues work under that unique name (DownloadWorker is dead code), so the
        // call was a silent no-op. Cancellation now goes through VideoDownloadService.
        downloadDao.updateProgress(videoId, DownloadStatus.FAILED, 0, 0)
        
        // Clean up partial files
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(videoId)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            VidlyLog.e("DownloadRepository", "Failed to clean up partial files for $videoId", e)
        }
    }

    override suspend fun pauseDownload(videoId: String) {
        val mission = missionDao.getMissionByVideoId(videoId)
        mission?.let {
            val intent = Intent(context, VideoDownloadService::class.java).apply {
                action = VideoDownloadService.ACTION_STOP
                putExtra("missionId", it.id)
            }
            context.startService(intent)
        }
        
        // FIX(BUG #12): removed no-op workManager.cancelUniqueWork(videoId).
        val entity = downloadDao.getDownloadById(videoId)
        entity?.let {
            downloadDao.updateDownload(it.copy(status = DownloadStatus.PAUSED))
        }
    }

    override suspend fun resumeDownload(videoId: String) {
        val mission = missionDao.getMissionByVideoId(videoId)
        mission?.let {
            startDownloadService(it.id)
            return
        }
        
        val entity = downloadDao.getDownloadById(videoId)
        entity?.let {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) {
                downloadDao.updateDownload(it.copy(status = DownloadStatus.WAITING))
                
                val newMission = DownloadMissionEntity(
                    videoId = it.videoId,
                    title = it.title,
                    quality = it.quality ?: "Unknown",
                    videoUrl = it.videoUrl,
                    audioUrl = it.audioUrl,
                    format = it.format,
                    outputFilePath = it.filePath
                )
                val missionId = missionDao.insertMission(newMission)
                startDownloadService(missionId)
            }
        }
    }

    override suspend fun pauseAllActiveDownloads() {
        val active = downloadDao.getActiveDownloads()
        active.forEach { 
            pauseDownload(it.videoId)
        }
    }

    override suspend fun resumeAllPausedDownloads() {
        val paused = downloadDao.getPausedDownloads()
        paused.forEach {
            resumeDownload(it.videoId)
        }
    }

    override suspend fun deleteDownload(videoId: String) {
        cancelDownload(videoId)
        val entity = downloadDao.getDownloadById(videoId)
        entity?.let {
            File(it.filePath).delete()
            downloadDao.deleteDownload(it)
        }
    }

    override suspend fun clearAllDownloads() {
        workManager.cancelAllWork()
        val allDownloads = downloadDao.getAllDownloadsList()
        allDownloads.forEach { entity ->
            try {
                val file = File(entity.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                VidlyLog.e("DownloadRepository", "Failed to delete file: ${entity.filePath}", e)
            }
        }
        downloadDao.clearAll()
    }

    override suspend fun saveToPublicStorage(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = downloadDao.getDownloadById(videoId) ?: return@withContext Result.failure(Exception("Download not found"))
            if (entity.status != DownloadStatus.COMPLETED) return@withContext Result.failure(Exception("Download not completed"))

            var file = File(entity.filePath)
            if (!file.exists()) {
                // Task: Resilient fallback check for alternate extensions
                val baseDir = context.getExternalFilesDir(null)
                val webmFile = File(baseDir, "$videoId.webm")
                val mp4File = File(baseDir, "$videoId.mp4")
                
                file = when {
                    webmFile.exists() -> webmFile
                    mp4File.exists() -> mp4File
                    else -> return@withContext Result.failure(Exception("File not found at ${entity.filePath}"))
                }
                
                // Sync the DB path if we found it elsewhere
                downloadDao.updateDownload(entity.copy(filePath = file.absolutePath))
            }

            // FEATURE (Audio downloads): derive extension/MIME from the actual file â€”
            // audio-only downloads are .m4a/.opus and belong in the Audio collection.
            val actualExt = file.extension.ifBlank {
                if (entity.format?.contains("webm", ignoreCase = true) == true) "webm" else "mp4"
            }
            val isAudio = actualExt == "m4a" || actualExt == "opus" || actualExt == "mp3"
            val mimeType = when (actualExt) {
                "webm" -> "video/webm"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "mp3" -> "audio/mpeg"
                else -> "video/mp4"
            }
            // FIX(LOW): video titles come straight from YouTube and can contain
            // characters illegal in file names (/ \ : ? * " < > |). Sanitize them.
            val safeTitle = entity.title.replace(Regex("[\\\\/:?*\"<>|]"), "_").trim().take(80)
            val displayName = "${safeTitle}_${entity.videoId}.$actualExt"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = if (isAudio) {
                        Environment.DIRECTORY_MUSIC + "/Vidly"
                    } else {
                        Environment.DIRECTORY_MOVIES + "/Vidly"
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collection = if (isAudio) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, contentValues) ?: return@withContext Result.failure(Exception("Failed to insert into MediaStore"))

            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            VidlyLog.e("DownloadRepository", "Failed to save to public storage", e)
            Result.failure(e)
        }
    }
}
