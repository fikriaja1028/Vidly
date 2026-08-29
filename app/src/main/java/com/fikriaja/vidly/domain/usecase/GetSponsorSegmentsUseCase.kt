/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.model.SponsorSegment
import com.fikriaja.vidly.domain.repository.SponsorBlockRepository
import javax.inject.Inject

class GetSponsorSegmentsUseCase @Inject constructor(
    private val repository: SponsorBlockRepository
) {
    suspend operator fun invoke(videoId: String): Result<List<SponsorSegment>> {
        return repository.getSponsorSegments(videoId)
    }
}
