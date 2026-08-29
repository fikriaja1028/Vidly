/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.data.repository

import android.content.Context
import android.net.Uri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fikriaja.vidly.data.local.*
import com.fikriaja.vidly.domain.repository.DataManagerRepository
import com.fikriaja.vidly.domain.repository.ImportProgress
import com.fikriaja.vidly.domain.usecase.UpdateUserInterestsUseCase
import com.fikriaja.vidly.workers.ImportWorker
import com.fikriaja.vidly.utils.VidlyLog
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataManagerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: VidlyDatabase,
    private val preferencesManager: PreferencesManager,
    private val gson: Gson
) : DataManagerRepository {

    override fun importTakeoutHistory(uri: Uri): Flow<ImportProgress> = flow {
        emit(ImportProgress.Loading(0f, "Queuing background import..."))
        
        val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(
                ImportWorker.KEY_URI to uri.toString(),
                ImportWorker.KEY_TYPE to ImportWorker.TYPE_HISTORY
            ))
            .build()
        
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(workRequest)

        // Observe the work status and emit progress
        val workInfoFlow = workManager.getWorkInfoByIdFlow(workRequest.id)
        
        workInfoFlow.collect { workInfo ->
            if (workInfo == null) return@collect
            
            val progress = workInfo.progress.getFloat(ImportWorker.PROGRESS_KEY, 0f)
            val status = workInfo.progress.getString(ImportWorker.STATUS_KEY) ?: "Importing..."
            
            when (workInfo.state) {
                androidx.work.WorkInfo.State.RUNNING -> {
                    emit(ImportProgress.Loading(progress, status))
                }
                androidx.work.WorkInfo.State.SUCCEEDED -> {
                    val count = workInfo.outputData.getInt(ImportWorker.COUNT_KEY, 0)
                    emit(ImportProgress.Success(count))
                    currentCoroutineContext().cancel()
                }
                androidx.work.WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString("error") ?: "Background import failed"
                    emit(ImportProgress.Error(error))
                    currentCoroutineContext().cancel()
                }
                // FIX(BUG #11): a CANCELLED worker left _isProcessing=true forever in
                // ImportManager, locking out all future imports until app restart.
                androidx.work.WorkInfo.State.CANCELLED -> {
                    emit(ImportProgress.Error("Import was cancelled"))
                    currentCoroutineContext().cancel()
                }
                else -> {}
            }
        }
    }.catch { e -> 
        if (e !is CancellationException) {
            emit(ImportProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    override fun importTakeoutSubscriptions(uri: Uri): Flow<ImportProgress> = flow {
        emit(ImportProgress.Loading(0f, "Queuing background import..."))
        
        val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(
                ImportWorker.KEY_URI to uri.toString(),
                ImportWorker.KEY_TYPE to ImportWorker.TYPE_SUBSCRIPTIONS
            ))
            .build()
        
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(workRequest)

        val workInfoFlow = workManager.getWorkInfoByIdFlow(workRequest.id)
        
        workInfoFlow.collect { workInfo ->
            if (workInfo == null) return@collect
            
            val progress = workInfo.progress.getFloat(ImportWorker.PROGRESS_KEY, 0f)
            val status = workInfo.progress.getString(ImportWorker.STATUS_KEY) ?: "Importing..."
            
            when (workInfo.state) {
                androidx.work.WorkInfo.State.RUNNING -> {
                    emit(ImportProgress.Loading(progress, status))
                }
                androidx.work.WorkInfo.State.SUCCEEDED -> {
                    val count = workInfo.outputData.getInt(ImportWorker.COUNT_KEY, 0)
                    emit(ImportProgress.Success(count))
                    currentCoroutineContext().cancel()
                }
                androidx.work.WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString("error") ?: "Background import failed"
                    emit(ImportProgress.Error(error))
                    currentCoroutineContext().cancel()
                }
                // FIX(BUG #11): a CANCELLED worker left _isProcessing=true forever in
                // ImportManager, locking out all future imports until app restart.
                androidx.work.WorkInfo.State.CANCELLED -> {
                    emit(ImportProgress.Error("Import was cancelled"))
                    currentCoroutineContext().cancel()
                }
                else -> {}
            }
        }
    }.catch { e ->
        if (e !is CancellationException) {
            emit(ImportProgress.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun getStreamForFile(uri: Uri, targetFileName: String): java.io.InputStream? {
        val cr = context.contentResolver
        val mimeType = cr.getType(uri)
        val isZip = mimeType == "application/zip" || 
                    uri.path?.endsWith(".zip", ignoreCase = true) == true ||
                    mimeType == "application/x-zip-compressed"
        
        if (!isZip) return try { cr.openInputStream(uri) } catch (e: Exception) { null }
        
        // Handle ZIP
        return try {
            val zipInputStream = ZipInputStream(cr.openInputStream(uri))
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                // Google Takeout often nests files like "Takeout/YouTube and YouTube Music/history/watch-history.json"
                val name = entry.name
                if (name.endsWith(targetFileName, ignoreCase = true)) {
                    return zipInputStream // Caller must close it
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            zipInputStream.close()
            null
        } catch (e: Exception) {
            VidlyLog.e("DataManager", "Error opening ZIP stream", e)
            null
        }
    }

    override suspend fun createBackup(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val history = database.historyDao().getAllHistoryStatic()
            val favorites = database.favoriteDao().getAllFavoritesStatic()
            val playlistFavorites = database.playlistFavoriteDao().getAllPlaylistFavoritesStatic()
            val subscriptions = database.subscriptionDao().getAllSubscriptionsStatic()
            val searchHistory = database.searchHistoryDao().getAllSearchHistoryStatic()
            val userInterests = database.userInterestDao().getAllInterestsStatic()
            val blacklist = database.blacklistDao().getAllBlacklistedStatic()
            
            val prefs = VidlyPreferences(
                isHistoryEnabled = preferencesManager.isHistoryEnabled.first(),
                isSearchHistoryPaused = preferencesManager.isSearchHistoryPaused.first(),
                isPipEnabled = preferencesManager.isPipEnabled.first(),
                isBackgroundPlayEnabled = preferencesManager.isBackgroundPlayEnabled.first(),
                isSubtitlesEnabled = preferencesManager.isSubtitlesEnabled.first(),
                isOnboardingCompleted = preferencesManager.isOnboardingCompleted.first(),
                isSearchGridView = preferencesManager.isSearchGridView.first(),
                isAutoUpdateEnabled = preferencesManager.isAutoUpdateEnabled.first(),
                isRecommendationsPaused = preferencesManager.isRecommendationsPaused.first()
            )

            val backup = VidlyBackup(
                version = 2,
                timestamp = System.currentTimeMillis(),
                history = history,
                favorites = favorites,
                playlistFavorites = playlistFavorites,
                subscriptions = subscriptions,
                searchHistory = searchHistory,
                userInterests = userInterests,
                blacklist = blacklist,
                preferences = prefs
            )

            val json = gson.toJson(backup)
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zos ->
                    val entry = ZipEntry("backup.json")
                    zos.putNextEntry(entry)
                    val writer = OutputStreamWriter(zos)
                    writer.write(json)
                    writer.flush()
                    zos.closeEntry()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreBackup(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // FIX(BUG #6): the old code only inspected the FIRST zip entry and still
            // returned success when it wasn't backup.json. Now we scan entries until we
            // find the backup, enforce a decompressed-size cap (zip-bomb protection) and
            // FAIL LOUDLY when the archive contains no backup.json.
            val MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024 // 256MB
            var restored = false

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null && !restored) {
                        if (entry.name.substringAfterLast('/').equals("backup.json", ignoreCase = true)) {
                            // Stream-parse with a hard cap on decompressed bytes
                            var consumed = 0L
                            val bufferedReader = java.io.BufferedReader(InputStreamReader(zis, Charsets.UTF_8))
                            val sb = StringBuilder()
                            val chunk = CharArray(64 * 1024)
                            while (true) {
                                val n = bufferedReader.read(chunk)
                                if (n == -1) break
                                consumed += n
                                if (consumed > MAX_DECOMPRESSED_BYTES) {
                                    throw Exception("Backup file too large (possible zip bomb)")
                                }
                                sb.append(chunk, 0, n)
                            }
                            
                            val backup = gson.fromJson(sb.toString(), VidlyBackup::class.java)
                                ?: return@withContext Result.failure(Exception("Failed to parse backup file"))
                            
                            database.runInTransaction {
                                database.historyDao().clearHistorySync()
                                database.favoriteDao().clearFavorites()
                                database.playlistFavoriteDao().clearPlaylistFavorites()
                                database.subscriptionDao().clearSubscriptions()
                                database.searchHistoryDao().clearAllSearchHistorySync()
                                database.userInterestDao().clearInterestsSync()
                                database.blacklistDao().getAllBlacklistedStaticSync().forEach { 
                                    database.blacklistDao().deleteSync(it) 
                                }

                                database.historyDao().insertAllIgnoreSync(backup.history)
                                database.favoriteDao().insertAllIgnoreSync(backup.favorites)
                                database.playlistFavoriteDao().insertAllIgnoreSync(backup.playlistFavorites)
                                database.subscriptionDao().insertAllIgnoreSync(backup.subscriptions)
                                database.searchHistoryDao().insertAllIgnoreSync(backup.searchHistory)
                                database.userInterestDao().insertAllIgnoreSync(backup.userInterests)
                                backup.blacklist?.let { list ->
                                    list.forEach { database.blacklistDao().insertSync(it) }
                                }
                            }

                            // Restore preferences outside transaction as DataStore is not part of Room transaction
                            coroutineScope {
                                launch { preferencesManager.setHistoryEnabled(backup.preferences.isHistoryEnabled) }
                                launch { preferencesManager.setSearchHistoryPaused(backup.preferences.isSearchHistoryPaused) }
                                launch { preferencesManager.setPipEnabled(backup.preferences.isPipEnabled) }
                                launch { preferencesManager.setBackgroundPlayEnabled(backup.preferences.isBackgroundPlayEnabled) }
                                launch { preferencesManager.setSubtitlesEnabled(backup.preferences.isSubtitlesEnabled) }
                                launch { preferencesManager.setOnboardingCompleted(backup.preferences.isOnboardingCompleted) }
                                launch { preferencesManager.setSearchGridView(backup.preferences.isSearchGridView) }
                                launch { preferencesManager.setAutoUpdateEnabled(backup.preferences.isAutoUpdateEnabled) }
                                launch { preferencesManager.setRecommendationsPaused(backup.preferences.isRecommendationsPaused) }
                            }
                            restored = true
                        }
                        entry = zis.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("Could not open backup file"))

            if (!restored) {
                return@withContext Result.failure(Exception("Not a valid Vidly backup: backup.json not found"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTakeoutTime(time: String?): Long {
        if (time == null) return System.currentTimeMillis()
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(time).toEpochMilli()
            } else {
                // FIX(BUG #13): pre-API 26 used to collapse EVERY imported timestamp to
                // "now", destroying the chronological order of the imported history.
                // Parse ISO-8601 manually with SimpleDateFormat instead.
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss"
                )
                val sdf = java.text.SimpleDateFormat(formats.first(), java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                var parsed = formats.asSequence()
                    .map { pattern ->
                        try {
                            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.parse(time)
                        } catch (e: Exception) { null }
                    }
                    .filterNotNull()
                    .firstOrNull()
                parsed?.time ?: System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

data class TakeoutHistoryItem(
    val title: String?,
    val titleUrl: String?,
    val subtitles: List<TakeoutSubtitle>?,
    val time: String?
)
data class TakeoutSubtitle(val name: String?)

data class VidlyBackup(
    val version: Int,
    val timestamp: Long,
    val history: List<HistoryEntity>,
    val favorites: List<FavoriteEntity>,
    val playlistFavorites: List<PlaylistFavoriteEntity>,
    val subscriptions: List<SubscriptionEntity>,
    val searchHistory: List<SearchHistoryEntity>,
    val userInterests: List<UserInterestEntity>,
    val blacklist: List<BlacklistEntity>? = emptyList(),
    val preferences: VidlyPreferences
)

data class VidlyPreferences(
    val isHistoryEnabled: Boolean,
    val isSearchHistoryPaused: Boolean,
    val isPipEnabled: Boolean,
    val isBackgroundPlayEnabled: Boolean,
    val isSubtitlesEnabled: Boolean,
    val isOnboardingCompleted: Boolean,
    val isSearchGridView: Boolean,
    val isAutoUpdateEnabled: Boolean = false,
    val isRecommendationsPaused: Boolean = false
)
