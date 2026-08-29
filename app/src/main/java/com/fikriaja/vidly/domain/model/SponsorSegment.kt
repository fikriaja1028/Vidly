/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.model

import kotlinx.serialization.Serializable
import androidx.annotation.Keep

@Keep
@Serializable
data class SponsorSegment(
    val category: String,
    val segment: List<Float>, // [start, end]
    val UUID: String
) {
    val startMs: Long get() = (segment.getOrNull(0) ?: 0f).times(1000).toLong()
    val endMs: Long get() = (segment.getOrNull(1) ?: 0f).times(1000).toLong()
}
