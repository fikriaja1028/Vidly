/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.model

import androidx.annotation.Keep

@Keep
data class CommentItem(
    val authorName: String,
    val authorThumbnailUrl: String?,
    val authorUrl: String?,
    val commentText: String,
    val publishedTime: String?,
    val likeCount: Int = 0,
    val isHeartedByUploader: Boolean = false,
    val replyCount: Int = 0,
    val commentId: String
)
