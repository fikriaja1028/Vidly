/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.workers

import com.fikriaja.vidly.data.local.DownloadDao
import com.fikriaja.vidly.data.local.DownloadStatus
import com.fikriaja.vidly.utils.VidlyLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoDownloadManager @Inject constructor(
    private val downloadDao: DownloadDao
) {
    private val missionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeMissions = ConcurrentHashMap<String, Job>()

    fun isMissionActive(videoId: String): Boolean = activeMissions.containsKey(videoId)

    suspend fun runMission(videoId: String, block: suspend () -> Unit) {
        val job = kotlin.coroutines.coroutineContext[Job] ?: return
        activeMissions[videoId] = job
        try {
            block()
        } finally {
            activeMissions.remove(videoId)
        }
    }

    fun pauseMission(videoId: String) {
        VidlyLog.d("VideoDownloadManager", "Pausing mission $videoId")
        activeMissions[videoId]?.cancel()
        activeMissions.remove(videoId)
        missionScope.launch {
            downloadDao.setDownloadStatus(videoId, DownloadStatus.PAUSED)
        }
    }
}
