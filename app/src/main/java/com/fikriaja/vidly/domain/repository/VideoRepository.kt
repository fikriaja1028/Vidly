/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.repository

import com.fikriaja.vidly.domain.model.ChannelDetails
import com.fikriaja.vidly.domain.model.ChannelInfoBasic
import com.fikriaja.vidly.domain.model.CommentItem
import com.fikriaja.vidly.domain.model.PaginatedList
import com.fikriaja.vidly.domain.model.PlaylistDetails
import com.fikriaja.vidly.domain.model.StreamBundle
import com.fikriaja.vidly.domain.model.VideoItem
import org.schabi.newpipe.extractor.Page

interface VideoRepository {
    suspend fun getStreamBundle(videoId: String, forceRefresh: Boolean = false): StreamBundle
    suspend fun getCachedStreamBundle(videoId: String): StreamBundle?
    suspend fun preloadStreamBundle(videoId: String)
    suspend fun fetchNextRelatedPage(videoId: String, page: Page): PaginatedList<VideoItem>
    suspend fun getChannelDetails(channelUrl: String): ChannelDetails
    suspend fun getChannelInfo(channelUrl: String): ChannelInfoBasic
    suspend fun fetchNextChannelVideosPage(channelUrl: String, page: Page): PaginatedList<VideoItem>
    suspend fun getTrendingVideos(): PaginatedList<VideoItem>
    suspend fun fetchNextTrendingPage(page: Page): PaginatedList<VideoItem>
    suspend fun getShortsVideos(): PaginatedList<VideoItem>
    suspend fun fetchNextShortsPage(page: Page): PaginatedList<VideoItem>
    suspend fun getPlaylistDetails(playlistUrl: String): PlaylistDetails
    suspend fun getComments(videoId: String): PaginatedList<CommentItem>
    suspend fun fetchNextCommentsPage(videoId: String, page: Page): PaginatedList<CommentItem>
    suspend fun getCommentReplies(videoId: String, comment: CommentItem): PaginatedList<CommentItem>
    suspend fun fetchNextCommentRepliesPage(videoId: String, commentId: String, page: Page): PaginatedList<CommentItem>
}
