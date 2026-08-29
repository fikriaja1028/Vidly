/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.work.Configuration
import com.fikriaja.vidly.BuildConfig
import com.fikriaja.vidly.data.network.YouTubeDownloader
import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.data.local.VidlyDatabase
import androidx.hilt.work.HiltWorkerFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.fikriaja.vidly.domain.repository.DownloadRepository
import com.fikriaja.vidly.utils.ConnectivityObserver
import com.fikriaja.vidly.utils.VidlyLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import java.io.File

@HiltAndroidApp
class VidlyApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var database: VidlyDatabase

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: Context): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        
        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        .build()
                )
            }

            createNotificationChannel()
            observeConnectivity()
            checkVersionAndCleanup()
            prewarmNetwork()
        } catch (e: Exception) {
            VidlyLog.e("VidlyApp", "Critical error during Application initialization", e)
        }
    }

    private fun checkVersionAndCleanup() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val lastVersion = preferencesManager.lastAppVersion.first()
                val currentVersion = BuildConfig.VERSION_CODE
                
                if (lastVersion != currentVersion) {
                    VidlyLog.i("VidlyApp", "Detected update from $lastVersion to $currentVersion. Performing cache cleanup.")
                    performUpdateCleanup()
                    preferencesManager.setLastAppVersion(currentVersion)
                }
            } catch (e: Exception) {
                VidlyLog.e("VidlyApp", "Version check or cleanup failed", e)
            }
        }
    }

    private suspend fun performUpdateCleanup() {
        try {
            // 1. Clear Feed Cache (Crucial for avoiding VideoItem serialization crashes)
            database.feedCacheDao().clearAll()
            
            // 2. Clear technical library caches (OkHttp, Coil, ExoPlayer)
            val cacheDir = applicationContext.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }
            
            VidlyLog.i("VidlyApp", "Update cleanup completed successfully")
        } catch (e: Exception) {
            VidlyLog.e("VidlyApp", "Error during update cleanup", e)
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun prewarmNetwork() {
        val youtubeRequest = Request.Builder().url("https://www.youtube.com").head().build()
        val gVideoRequest = Request.Builder().url("https://www.googlevideo.com").head().build()

        val callback = object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                VidlyLog.w("VidlyApp", "Network pre-warming failed for ${call.request().url}: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                VidlyLog.d("VidlyApp", "Network pre-warmed for ${call.request().url}")
            }
        }

        okHttpClient.newCall(youtubeRequest).enqueue(callback)
        okHttpClient.newCall(gVideoRequest).enqueue(callback)
    }

    private fun observeConnectivity() {
        connectivityObserver.observe()
            .onEach { status ->
                when (status) {
                    ConnectivityObserver.Status.Available -> {
                        downloadRepository.resumeAllPausedDownloads()
                    }
                    ConnectivityObserver.Status.Lost, ConnectivityObserver.Status.Unavailable -> {
                        downloadRepository.pauseAllActiveDownloads()
                    }
                    else -> {}
                }
            }
            .launchIn(applicationScope)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val descriptionText = "Shows download progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("download_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
