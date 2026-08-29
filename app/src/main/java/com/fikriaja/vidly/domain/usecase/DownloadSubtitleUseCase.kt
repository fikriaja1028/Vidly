/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fikriaja.vidly.domain.model.SubtitleItem
import com.fikriaja.vidly.utils.VidlyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * FEATURE (Subtitle downloads): downloads a subtitle track as a .vtt file.
 *
 * YouTube timedtext URLs usually point at json3/srv3 payloads; they are
 * rewritten to the vtt format before fetching. Files are saved to the public
 * Downloads/Vidly directory via MediaStore on Android 10+, and to the
 * app's external files directory on older versions (no legacy storage
 * permission required).
 */
class DownloadSubtitleUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: okhttp3.OkHttpClient
) {
    suspend operator fun invoke(videoTitle: String, videoId: String, subtitle: SubtitleItem): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (subtitle.url.isBlank()) {
                    return@withContext Result.failure(Exception("Subtitle URL is empty"))
                }

                val vttUrl = subtitle.url
                    .replace("fmt=json3", "fmt=vtt")
                    .replace("fmt=srv3", "fmt=vtt")
                    .replace("fmt=ttml", "fmt=vtt")
                    .let { if (!it.contains("fmt=")) it + (if (it.contains("?")) "&" else "?") + "fmt=vtt" else it }

                val request = okhttp3.Request.Builder()
                    .url(vttUrl)
                    .header("User-Agent", com.fikriaja.vidly.utils.Constants.DEFAULT_USER_AGENT)
                    .build()

                val body = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Subtitle download failed: HTTP ${response.code}"))
                    }
                    response.body?.string() ?: return@withContext Result.failure(Exception("Empty subtitle body"))
                }

                if (!body.startsWith("WEBVTT")) {
                    return@withContext Result.failure(Exception("Server did not return a WebVTT subtitle"))
                }

                val safeTitle = videoTitle.replace(Regex("[\\\\/:?*\"<>|]"), "_").trim().take(60)
                val fileName = "${safeTitle}_${videoId}.${subtitle.languageTag}.vtt"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/vtt")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vidly")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: return@withContext Result.failure(Exception("Failed to create subtitle file"))
                    resolver.openOutputStream(uri)?.use { output ->
                        output.write(body.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: return@withContext Result.failure(Exception("Failed to open output stream"))
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Result.success(fileName)
                } else {
                    // Pre-Android 10: save inside the app's own external directory
                    // (no WRITE_EXTERNAL_STORAGE permission needed).
                    val dir = File(context.getExternalFilesDir(null), "Subtitles").apply { mkdirs() }
                    val outFile = File(dir, fileName)
                    outFile.writeText(body, Charsets.UTF_8)
                    VidlyLog.d("DownloadSubtitleUseCase", "Subtitle saved to ${outFile.absolutePath}")
                    Result.success(outFile.absolutePath)
                }
            } catch (e: Exception) {
                VidlyLog.e("DownloadSubtitleUseCase", "Subtitle download failed", e)
                Result.failure(e)
            }
        }
}
