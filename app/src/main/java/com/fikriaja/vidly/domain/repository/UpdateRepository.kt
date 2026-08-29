/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface UpdateRepository {
    val updateInfo: StateFlow<UpdateInfo>
    suspend fun checkForUpdates()
}

data class UpdateInfo(
    val hasUpdate: Boolean = false,
    val latestVersion: String = "",
    val releaseNotes: String = "",
    val updateUrl: String = ""
)
