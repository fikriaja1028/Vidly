/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.repository

import com.fikriaja.vidly.data.local.FavoriteEntity
import com.fikriaja.vidly.data.local.HistoryEntity
import com.fikriaja.vidly.data.local.PlaylistFavoriteEntity
import com.fikriaja.vidly.data.local.SearchHistoryEntity
import com.fikriaja.vidly.data.local.SubscriptionEntity
import com.fikriaja.vidly.data.local.UserInterestEntity
import com.fikriaja.vidly.domain.model.VideoItem
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    // History
    fun getHistory(): Flow<List<HistoryEntity>>
    suspend fun getRecentHistory(limit: Int): List<HistoryEntity>
    suspend fun addToHistory(history: HistoryEntity)
    suspend fun updateWatchProgress(videoId: String, progressMs: Long, durationMs: Long)
    suspend fun removeFromHistory(videoId: String)
    suspend fun clearHistory()
    // FEATURE (Private mode): deletes history + search-history entries created
    // at or after the given epoch-ms timestamp (i.e. during a private session).
    suspend fun purgeDataSince(since: Long)
    suspend fun getWatchProgressForVideos(videoIds: List<String>): Map<String, Float?>

    // Favorites
    fun getFavorites(): Flow<List<FavoriteEntity>>
    fun isFavorite(videoId: String): Flow<Boolean>
    suspend fun addToFavorites(favorite: FavoriteEntity)
    suspend fun removeFromFavorites(favorite: FavoriteEntity)

    // Playlist Favorites
    fun getPlaylistFavorites(): Flow<List<PlaylistFavoriteEntity>>
    fun isPlaylistFavorite(playlistId: String): Flow<Boolean>
    suspend fun addToPlaylistFavorites(favorite: PlaylistFavoriteEntity)
    suspend fun removeFromPlaylistFavorites(favorite: PlaylistFavoriteEntity)

    // Subscriptions
    fun getSubscriptions(): Flow<List<SubscriptionEntity>>
    fun isSubscribed(channelId: String): Flow<Boolean>
    suspend fun subscribe(subscription: SubscriptionEntity)
    suspend fun unsubscribe(subscription: SubscriptionEntity)
    suspend fun unsubscribeByIdFuzzy(channelId: String)

    // Search History
    fun getSearchHistory(): Flow<List<SearchHistoryEntity>>
    suspend fun addSearchQuery(query: String)
    suspend fun deleteSearchQuery(query: String)
    suspend fun clearSearchHistory()

    // User Interests
    suspend fun getTopInterests(limit: Int): List<UserInterestEntity>
    suspend fun updateInterest(keyword: String, weightDelta: Float)
    suspend fun applyInterestDecay(decayFactor: Float)
    suspend fun clearAllInterests()
    suspend fun hasInterests(): Boolean

    // Blacklist
    fun getBlacklist(): Flow<List<com.fikriaja.vidly.data.local.BlacklistEntity>>
    suspend fun getBlacklistStatic(): List<com.fikriaja.vidly.data.local.BlacklistEntity>
    suspend fun addToBlacklist(id: String, type: com.fikriaja.vidly.data.local.BlacklistType)
    suspend fun removeFromBlacklist(id: String)
    suspend fun isBlacklisted(id: String): Boolean

    // Local Playlists
    fun getLocalPlaylists(): Flow<List<com.fikriaja.vidly.data.local.LocalPlaylistEntity>>
    suspend fun createLocalPlaylist(name: String, description: String? = null): Long
    suspend fun deleteLocalPlaylist(playlist: com.fikriaja.vidly.data.local.LocalPlaylistEntity)
    fun getVideosForLocalPlaylist(playlistId: Int): Flow<List<com.fikriaja.vidly.data.local.LocalPlaylistVideoEntity>>
    suspend fun addVideoToLocalPlaylist(playlistId: Int, video: VideoItem)
    suspend fun removeVideoFromLocalPlaylist(playlistId: Int, videoId: String)
    suspend fun isVideoInLocalPlaylist(playlistId: Int, videoId: String): Boolean
    fun isVideoInAnyLocalPlaylist(videoId: String): Flow<Boolean>
    fun getAllSavedVideoIds(): Flow<List<String>>
    fun getPlaylistsContainingVideo(videoId: String): Flow<List<Int>>

    // Feed Cache
    fun getCachedFeed(key: String): Flow<com.fikriaja.vidly.data.local.FeedCacheEntity?>
    suspend fun updateCachedFeed(key: String, videos: List<VideoItem>)
}
