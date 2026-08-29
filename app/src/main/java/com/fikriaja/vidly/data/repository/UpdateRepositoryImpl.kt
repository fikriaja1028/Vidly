/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.data.repository

import com.fikriaja.vidly.BuildConfig
import com.fikriaja.vidly.data.network.GitHubRelease
import com.fikriaja.vidly.domain.repository.UpdateInfo
import com.fikriaja.vidly.domain.repository.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val client: HttpClient
) : UpdateRepository {

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    override val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    override suspend fun checkForUpdates() {
        fetchUpdate("fikriaja1028/Vidly", _updateInfo)
    }

    private suspend fun fetchUpdate(repo: String, flow: MutableStateFlow<UpdateInfo>) {
        try {
            val response: GitHubRelease = client.get("https://api.github.com/repos/$repo/releases/latest").body()
            val latestVersion = response.tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            val hasUpdate = isVersionNewer(currentVersion, latestVersion)

            flow.value = UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                releaseNotes = response.body ?: "",
                updateUrl = response.htmlUrl ?: ""
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }
}
