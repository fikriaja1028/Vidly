
package com.fikriaja.vidly.data.network

import com.fikriaja.vidly.data.local.DownloadChunkEntity
import com.fikriaja.vidly.data.local.MissionDao
import com.fikriaja.vidly.utils.Constants
import com.fikriaja.vidly.utils.VidlyLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

class ParallelDownloader(
    private val client: OkHttpClient,
    private val missionDao: MissionDao
) {
    private val semaphore = Semaphore(4) // Max 4 parallel chunks
    private val CHUNK_SIZE = 4L * 1024 * 1024 // 4MB

    @Volatile
    private var bytesSinceCheckpoint = 0L

    private companion object {
        const val CHECKPOINT_INTERVAL = 512L * 1024 // persist chunk progress every 512KB
    }

    suspend fun download(
        url: String,
        outputFile: File,
        missionId: Long,
        type: com.fikriaja.vidly.data.local.ChunkType,
        onProgress: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val totalSize = getFileSize(url)
        if (totalSize == -403L) throw Exception("403")
        if (totalSize <= 0) throw Exception("Failed to get file size")
        
        // Pre-allocate file
        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(totalSize)
        }

        // FIX(BUG #8): If the mission was previously downloaded against a DIFFERENT
        // stream (re-resolved URL), stored chunks may cover a stale byte range.
        // Recreate all chunks whenever their coverage doesn't match the fresh size.
        val existingChunks = missionDao.getChunksForMission(missionId).filter { it.type == type }
        val staleChunks = existingChunks.isNotEmpty() && (
            existingChunks.maxOf { it.endByte } != totalSize - 1 ||
            existingChunks.sumOf { it.endByte - it.startByte + 1 } != totalSize
        )
        val chunks = if (existingChunks.isEmpty() || staleChunks) {
            if (staleChunks) {
                VidlyLog.w("ParallelDownloader", "Stale chunk map detected for mission $missionId ($type); recreating chunks")
                missionDao.deleteChunksForMission(missionId, type)
            }
            createChunks(missionId, totalSize, type)
        } else {
            existingChunks
        }

        val downloadedBytes = AtomicLong(chunks.sumOf { it.bytesDownloaded })
        
        val deferreds = chunks.filter { !it.isCompleted }.map { chunk ->
            async {
                downloadChunkWithRetry(url, outputFile, chunk, downloadedBytes, onProgress)
            }
        }

        deferreds.awaitAll()
        totalSize
    }

    private suspend fun createChunks(
        missionId: Long,
        totalSize: Long,
        type: com.fikriaja.vidly.data.local.ChunkType
    ): List<DownloadChunkEntity> {
        val chunks = mutableListOf<DownloadChunkEntity>()
        var start = 0L
        var index = 0
        while (start < totalSize) {
            val end = (start + CHUNK_SIZE - 1).coerceAtMost(totalSize - 1)
            val chunk = DownloadChunkEntity(
                missionId = missionId,
                chunkIndex = index++,
                startByte = start,
                endByte = end,
                type = type
            )
            val id = missionDao.insertChunk(chunk)
            chunks.add(chunk.copy(id = id))
            start = end + 1
        }
        return chunks
    }

    private suspend fun downloadChunkWithRetry(
        url: String,
        outputFile: File,
        chunk: DownloadChunkEntity,
        downloadedBytes: AtomicLong,
        onProgress: (Long) -> Unit
    ) {
        var attempt = 0
        val maxRetries = 5
        // FIX(BUG #8): downloadChunk() re-reads the persisted checkpoint from the DB
        // on every attempt, so retries resume from where the last attempt actually
        // stopped instead of re-downloading (and re-counting) the entire chunk.
        while (attempt < maxRetries) {
            try {
                downloadChunk(url, outputFile, chunk, downloadedBytes, onProgress)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxRetries) throw e
                val delayTime = (1000L * attempt * attempt).coerceAtMost(30000L)
                delay(delayTime)
                VidlyLog.w("ParallelDownloader", "Retrying chunk ${chunk.chunkIndex} (attempt $attempt)")
            }
        }
    }

    private suspend fun downloadChunk(
        url: String,
        outputFile: File,
        chunk: DownloadChunkEntity,
        downloadedBytes: AtomicLong,
        onProgress: (Long) -> Unit
    ) {
        semaphore.acquire()
        try {
            // Refresh the persisted progress for this chunk on every attempt so that
            // retries resume from where the LAST attempt actually stopped in the DB.
            val persistedChunk = missionDao.getChunkById(chunk.id) ?: chunk
            val start = persistedChunk.startByte + persistedChunk.bytesDownloaded
            if (start > persistedChunk.endByte) {
                missionDao.updateChunkProgress(persistedChunk.id, persistedChunk.bytesDownloaded, true)
                return
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Constants.DEFAULT_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$start-${persistedChunk.endByte}")
                .build()

            client.newCall(request).execute().use { response ->
                // FIX(BUG #7): verify we really got a 206 Partial Content. If a server
                // (or transparent proxy) ignores the Range header and replies 200 with
                // the FULL body, blindly writing it into this chunk's slot corrupts
                // the assembled output file.
                if (response.code != 206) {
                    throw Exception("Chunk download failed: expected 206 Partial Content but got ${response.code}")
                }
                val body = response.body ?: throw Exception("Empty body")
                
                RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var currentChunkProgress = persistedChunk.bytesDownloaded
                    
                    body.byteStream().use { input ->
                        while (input.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            raf.write(buffer, 0, read)
                            currentChunkProgress += read
                            downloadedBytes.addAndGet(read.toLong())
                            onProgress(downloadedBytes.get())
                            
                            // FIX(BUG #8): old code checkpointed on
                            // `progress % 512KB == 0`, which almost never fired with
                            // partial network reads (64KB buffer) â†’ resume re-downloaded
                            // whole chunks. Checkpoint on a byte interval instead.
                            bytesSinceCheckpoint += read
                            if (bytesSinceCheckpoint >= CHECKPOINT_INTERVAL) {
                                bytesSinceCheckpoint = 0
                                missionDao.updateChunkProgress(persistedChunk.id, currentChunkProgress, false)
                            }
                        }
                    }
                    missionDao.updateChunkProgress(persistedChunk.id, currentChunkProgress, true)
                }
            }
        } finally {
            semaphore.release()
        }
    }

    suspend fun getFileSize(url: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.DEFAULT_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .head()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 403) return@withContext -403L
                if (response.isSuccessful) {
                    return@withContext response.header("Content-Length")?.toLongOrNull() ?: response.body?.contentLength() ?: 0L
                } else {
                    // Try GET with Range 0-0 if HEAD fails
                    val getRequest = Request.Builder()
                        .url(url)
                        .header("User-Agent", Constants.DEFAULT_USER_AGENT)
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(getRequest).execute().use { getResponse ->
                        if (getResponse.code == 403) return@withContext -403L
                        val contentRange = getResponse.header("Content-Range")
                        return@withContext contentRange?.substringAfterLast("/")?.toLongOrNull() ?: 0L
                    }
                }
            }
        } catch (e: Exception) {
            VidlyLog.e("ParallelDownloader", "Failed to get file size for $url", e)
            0L
        }
    }
}
