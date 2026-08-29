/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import com.fikriaja.vidly.data.local.HistoryEntity
import com.fikriaja.vidly.data.local.VidlyDatabase
import com.fikriaja.vidly.data.local.SubscriptionEntity
import com.fikriaja.vidly.domain.repository.VideoRepository
import com.fikriaja.vidly.domain.usecase.UpdateUserInterestsUseCase
import com.fikriaja.vidly.R
import com.fikriaja.vidly.utils.VidlyLog
import com.fikriaja.vidly.utils.VideoUtils
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val database: VidlyDatabase,
    private val videoRepository: VideoRepository,
    private val updateUserInterestsUseCase: UpdateUserInterestsUseCase,
    private val gson: Gson
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URI = "uri"
        const val KEY_TYPE = "type"
        const val TYPE_HISTORY = "history"
        const val TYPE_SUBSCRIPTIONS = "subscriptions"
        
        const val PROGRESS_KEY = "progress"
        const val STATUS_KEY = "status"
        const val COUNT_KEY = "count"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriString = inputData.getString(KEY_URI) ?: return@withContext Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: return@withContext Result.failure()
        val uri = Uri.parse(uriString)

        try {
            when (type) {
                TYPE_HISTORY -> importHistory(uri)
                TYPE_SUBSCRIPTIONS -> importSubscriptions(uri)
                else -> Result.failure()
            }
        } catch (e: Exception) {
            VidlyLog.e("ImportWorker", "Import failed", e)
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    private suspend fun importHistory(uri: Uri): Result {
        updateProgress(0f, context.getString(R.string.loading))
        
        var importedCount = 0
        var malformedCount = 0
        val inputStream = getStreamForFile(uri, listOf("watch-history.json", "MyActivity.json")) 
            ?: return Result.failure(Data.Builder().putString("error", context.getString(R.string.error_history_not_found)).build())

        inputStream.use { stream ->
            val reader = JsonReader(InputStreamReader(stream, "UTF-8"))
            try {
                reader.beginArray()
                
                val batchSize = 100
                val currentBatch = mutableListOf<HistoryEntity>()
                
                while (reader.hasNext()) {
                    if (isStopped) return Result.retry()
                    
                    try {
                        // Task 4: Robust deserialization with skipValue fallback
                        val item = try {
                            gson.fromJson<com.fikriaja.vidly.data.repository.TakeoutHistoryItem>(reader, com.fikriaja.vidly.data.repository.TakeoutHistoryItem::class.java)
                        } catch (e: Exception) {
                            VidlyLog.e("ImportWorker", "Malformed history item skipped", e)
                            reader.skipValue()
                            null
                        }

                        if (item != null) {
                            val videoId = VideoUtils.extractVideoId(item.titleUrl)
                            
                            if (videoId.isNotBlank() && item.title != null) {
                                val entity = HistoryEntity(
                                    videoId = videoId,
                                    title = item.title.removePrefix("Watched "),
                                    thumbnailUrl = VideoUtils.getBestThumbnailUrl(videoId),
                                    uploaderName = item.subtitles?.firstOrNull()?.name ?: "Unknown",
                                    timestamp = parseTakeoutTime(item.time)
                                )
                                currentBatch.add(entity)
                            }
                        }
                    } catch (e: Exception) {
                        malformedCount++
                        try { reader.skipValue() } catch (ex: Exception) {}
                        continue
                    }

                    if (currentBatch.size >= batchSize) {
                        database.historyDao().insertAllIgnoreSync(currentBatch)
                        importedCount += currentBatch.size
                        currentBatch.clear()
                        updateProgress(0.5f, context.getString(R.string.importing_items, importedCount))
                    }
                }
                
                if (currentBatch.isNotEmpty()) {
                    database.historyDao().insertAllIgnoreSync(currentBatch)
                    importedCount += currentBatch.size
                }
                
                reader.endArray()
            } catch (e: Exception) {
                VidlyLog.e("ImportWorker", "History parsing error", e)
                return Result.failure(Data.Builder().putString("error", context.getString(R.string.error_malformed_history)).build())
            }
        }
        
        return Result.success(Data.Builder().putInt(COUNT_KEY, importedCount).build())
    }

    private suspend fun importSubscriptions(uri: Uri): Result {
        updateProgress(0f, context.getString(R.string.loading))
        
        val channelIds = mutableListOf<String>()
        val inputStream = getStreamForFile(uri, listOf("subscriptions.csv")) 
            ?: return Result.failure(Data.Builder().putString("error", context.getString(R.string.error_subscriptions_not_found)).build())

        inputStream.use { stream ->
            val reader = stream.bufferedReader()
            try {
                reader.readLine() // Skip header
                
                reader.forEachLine { line ->
                    try {
                        // FIX(BUG #13): naive line.split(",") broke on quoted fields
                        // containing commas (standard in YouTube Takeout CSVs). Split
                        // respecting double-quoted sections instead.
                        val parts = splitCsvLine(line)
                        if (parts.isNotEmpty()) {
                            val channelId = parts[0].trim().removePrefix("\"").removeSuffix("\"")
                            if (channelId.startsWith("UC")) {
                                channelIds.add(channelId)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                return Result.failure(Data.Builder().putString("error", context.getString(R.string.error_malformed_csv)).build())
            }
        }

        if (channelIds.isEmpty()) return Result.success(Data.Builder().putInt(COUNT_KEY, 0).build())

        var importedCount = 0
        val totalCount = channelIds.size

        channelIds.chunked(5).forEachIndexed { chunkIndex, chunk ->
            if (isStopped) return@forEachIndexed
            
            val startIndex = chunkIndex * 5
            updateProgress(
                startIndex.toFloat() / totalCount,
                "Importing channels ${startIndex + 1} to ${minOf(startIndex + 5, totalCount)} of $totalCount..."
            )

            val results = chunk.map { channelId ->
                try {
                    val details = videoRepository.getChannelInfo(channelId)
                    SubscriptionEntity(
                        channelId = channelId,
                        name = details.name,
                        thumbnailUrl = details.avatarUrl,
                        subscriberCount = details.subscriberCount
                    )
                } catch (e: Exception) {
                    SubscriptionEntity(channelId = channelId, name = "Unknown Channel")
                }
            }
            
            results.forEach { database.subscriptionDao().insertSubscription(it) }
            importedCount += results.size
            delay(300L) // Prevent throttling
        }

        return Result.success(Data.Builder().putInt(COUNT_KEY, importedCount).build())
    }

    private fun getStreamForFile(uri: Uri, targetFileNames: List<String>): InputStream? {
        val cr = context.contentResolver
        val type = cr.getType(uri)
        val isZip = type == "application/zip" || 
                    uri.path?.endsWith(".zip", ignoreCase = true) == true ||
                    type == "application/x-zip-compressed"

        if (!isZip) {
            // Check if the single file matches any of our targets
            val fileName = getFileName(uri) ?: ""
            return if (targetFileNames.any { fileName.endsWith(it, ignoreCase = true) }) {
                cr.openInputStream(uri)
            } else {
                null
            }
        }

        // Search in ZIP
        return try {
            val zipInputStream = ZipInputStream(cr.openInputStream(uri))
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                val name = entry.name
                if (targetFileNames.any { name.endsWith(it, ignoreCase = true) }) {
                    return zipInputStream
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            zipInputStream.close()
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * FIX(BUG #13): CSV-aware splitter that respects double-quoted fields so that
     * values containing commas (channel titles etc. in Takeout CSV) don't shift columns.
     */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun parseTakeoutTime(time: String?): Long {
        if (time == null) return System.currentTimeMillis()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.time.Instant.parse(time).toEpochMilli()
            } else {
                // FIX(BUG #13): pre-API 26 collapsed every imported timestamp to "now",
                // destroying chronological order. Parse ISO-8601 with SimpleDateFormat.
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss"
                )
                formats.asSequence()
                    .map { pattern ->
                        try {
                            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.parse(time)
                        } catch (e: Exception) { null }
                    }
                    .filterNotNull()
                    .firstOrNull()?.time ?: System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private suspend fun updateProgress(progress: Float, status: String) {
        val data = Data.Builder()
            .putFloat(PROGRESS_KEY, progress)
            .putString(STATUS_KEY, status)
            .build()
        setProgress(data)
        setForeground(createForegroundInfo(status, (progress * 100).toInt()))
    }

    private fun createForegroundInfo(status: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, "download_channel")
            .setContentTitle("Importing Data")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, notification)
        }
    }
}
