/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.data.repository

import com.fikriaja.vidly.domain.model.SponsorSegment
import com.fikriaja.vidly.domain.repository.SponsorBlockRepository
import com.fikriaja.vidly.utils.VidlyLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SponsorBlockRepositoryImpl @Inject constructor(
    private val client: HttpClient
) : SponsorBlockRepository {
    
    private val baseUrl = "https://sponsor.ajay.app/api/skipSegments"

    override suspend fun getSponsorSegments(videoId: String): Result<List<SponsorSegment>> = withContext(Dispatchers.IO) {
        try {
            val response = client.get(baseUrl) {
                url {
                    parameters.append("videoID", videoId)
                    // We can specify categories if needed, by default it might return all or some.
                    // categories=["sponsor","selfpromo","interaction","intro","outro","preview","music_offtopic"]
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val segments = response.body<List<SponsorSegment>>()
                Result.success(segments)
            } else if (response.status == HttpStatusCode.NotFound) {
                // No segments found for this video
                Result.success(emptyList())
            } else {
                Result.failure(Exception("Failed to fetch segments: ${response.status}"))
            }
        } catch (e: Exception) {
            VidlyLog.e("SponsorBlock", "Error fetching segments for $videoId", e)
            Result.failure(e)
        }
    }
}
