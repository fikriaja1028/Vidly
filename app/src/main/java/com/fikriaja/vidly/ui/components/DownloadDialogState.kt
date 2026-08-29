/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.components

import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.model.StreamBundle

sealed class DownloadDialogState {
    object Idle : DownloadDialogState()
    data class Loading(val video: VideoItem) : DownloadDialogState()
    data class ShowDialog(val video: VideoItem, val bundle: StreamBundle) : DownloadDialogState()
}
