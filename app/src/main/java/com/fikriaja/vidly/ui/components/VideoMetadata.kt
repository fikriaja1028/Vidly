/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.fikriaja.vidly.utils.VideoUtils

/**
 * Reusable metadata row for video list items.
 * Displays: Channel Name â€¢ Views â€¢ Upload Date
 */
@Composable
fun VideoMetadata(
    uploaderName: String,
    viewCount: Long,
    uploadDate: String?,
    onChannelClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onChannelClick != null) Modifier.clickable(onClick = onChannelClick)
                else Modifier
            )
    ) {
        val hasUploader = uploaderName.isNotBlank()
        val hasUploadDate = !uploadDate.isNullOrBlank()

        // Channel Name
        if (hasUploader) {
            Text(
                text = uploaderName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        // Views (Always shown as long as count is 0 or more, but adds bullet if uploader exists)
        val formattedViews = VideoUtils.formatViewCount(viewCount)
        Text(
            text = if (hasUploader) " â€¢ $formattedViews" 
                   else if (viewCount == -1L) formattedViews
                   else "$formattedViews views",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1
        )

        // Upload Date
        if (hasUploadDate) {
            Text(
                text = " â€¢ ${VideoUtils.formatUploadDate(uploadDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}
