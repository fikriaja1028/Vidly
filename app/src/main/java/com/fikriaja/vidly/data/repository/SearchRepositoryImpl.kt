/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.data.repository

import com.fikriaja.vidly.domain.model.PaginatedList
import com.fikriaja.vidly.domain.model.SearchSort
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.model.SearchItem
import com.fikriaja.vidly.domain.model.PlaylistItem
import com.fikriaja.vidly.domain.model.UploadDateFilter
import com.fikriaja.vidly.domain.model.DurationFilter
import com.fikriaja.vidly.domain.repository.SearchRepository
import com.fikriaja.vidly.data.network.NewPipeInitializer
import com.fikriaja.vidly.utils.Constants
import com.fikriaja.vidly.utils.VidlyLog
import com.fikriaja.vidly.utils.VideoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val initializer: NewPipeInitializer
) : SearchRepository {
    override suspend fun search(
        query: String,
        sort: SearchSort,
        uploadDate: UploadDateFilter,
        duration: DurationFilter
    ): PaginatedList<SearchItem> {
        initializer.ensureInitialized()
        return withContext(Dispatchers.IO) {
            try {
                val youtubeService = ServiceList.YouTube

                val sortFilter = sort.value
                val contentFilters = mutableListOf<String>()
                
                // Base filter
                if (sort == SearchSort.UPLOAD_DATE) {
                    contentFilters.add("videos")
                } else {
                    contentFilters.add("all")
                }

                // Add upload date filter if not ALL
                if (uploadDate != UploadDateFilter.ALL) {
                    contentFilters.add(uploadDate.value)
                }

                // Add duration filter if not ALL
                if (duration != DurationFilter.ALL) {
                    contentFilters.add(duration.value)
                }
                
                VidlyLog.d("SearchRepository", "Searching for: $query with sort: $sortFilter, filters: $contentFilters")
                
                val extractor = youtubeService.getSearchExtractor(
                    query,
                    contentFilters,
                    sortFilter
                )
                extractor.fetchPage()

                val page = extractor.initialPage
                val items = page.items.mapNotNull { item ->
                    when (item) {
                        is StreamInfoItem -> SearchItem.Video(mapToVideoItem(item))
                        is ChannelInfoItem -> SearchItem.Channel(
                            id = item.url, // Usually full URL in NewPipe
                            name = item.name ?: "Unknown Channel",
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url,
                            subscriberCount = item.subscriberCount,
                            description = item.description
                        )
                        is PlaylistInfoItem -> SearchItem.Playlist(
                            PlaylistItem(
                                id = VideoUtils.extractPlaylistId(item.url),
                                title = item.name ?: "Unknown Playlist",
                                thumbnailUrl = item.thumbnails?.firstOrNull()?.url ?: "",
                                uploaderName = item.uploaderName ?: "Unknown Channel",
                                uploaderUrl = item.uploaderUrl,
                                streamCount = item.streamCount
                            )
                        )
                        else -> null
                    }
                }

                PaginatedList(items, if (page.hasNextPage()) page.nextPage else null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                PaginatedList(emptyList(), null)
            }
        }
    }

    override suspend fun fetchNextPage(
        query: String,
        sort: SearchSort,
        uploadDate: UploadDateFilter,
        duration: DurationFilter,
        page: Page
    ): PaginatedList<SearchItem> {
        initializer.ensureInitialized()
        return withContext(Dispatchers.IO) {
            try {
                val youtubeService = ServiceList.YouTube

                val sortFilter = sort.value
                val contentFilters = mutableListOf<String>()
                
                if (sort == SearchSort.UPLOAD_DATE) {
                    contentFilters.add("videos")
                } else {
                    contentFilters.add("all")
                }

                if (uploadDate != UploadDateFilter.ALL) {
                    contentFilters.add(uploadDate.value)
                }

                if (duration != DurationFilter.ALL) {
                    contentFilters.add(duration.value)
                }
                
                VidlyLog.d("SearchRepository", "Fetching next page for: $query with filters: $contentFilters")
                
                val extractor = youtubeService.getSearchExtractor(
                    query,
                    contentFilters,
                    sortFilter
                )
                val nextPage = extractor.getPage(page)
                
                val items = nextPage.items.mapNotNull { item ->
                    when (item) {
                        is StreamInfoItem -> SearchItem.Video(mapToVideoItem(item))
                        is ChannelInfoItem -> SearchItem.Channel(
                            id = item.url,
                            name = item.name ?: "Unknown Channel",
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url,
                            subscriberCount = item.subscriberCount,
                            description = item.description
                        )
                        is PlaylistInfoItem -> SearchItem.Playlist(
                            PlaylistItem(
                                id = VideoUtils.extractPlaylistId(item.url),
                                title = item.name ?: "Unknown Playlist",
                                thumbnailUrl = item.thumbnails?.firstOrNull()?.url ?: "",
                                uploaderName = item.uploaderName ?: "Unknown Channel",
                                uploaderUrl = item.uploaderUrl,
                                streamCount = item.streamCount
                            )
                        )
                        else -> null
                    }
                }

                PaginatedList(items, if (nextPage.hasNextPage()) nextPage.nextPage else null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                PaginatedList(emptyList(), null)
            }
        }
    }

    private fun mapToVideoItem(item: StreamInfoItem): VideoItem {
        val videoId = VideoUtils.extractVideoId(item.url)
        val uploadDate = item.textualUploadDate ?: item.uploadDate?.offsetDateTime()?.toLocalDate()?.toString() ?: ""
        val rawUploadDate = item.uploadDate?.instant?.toEpochMilli() ?: VideoUtils.parseTextualUploadDate(item.textualUploadDate)
        
        return VideoItem(
            id = videoId,
            title = item.name ?: "Unknown Title",
            thumbnailUrl = VideoUtils.getThumbnailForList(videoId),
            uploaderName = item.uploaderName ?: "Unknown Channel",
            uploaderUrl = item.uploaderUrl ?: "",
            uploaderThumbnailUrl = item.uploaderAvatars?.firstOrNull()?.url,
            viewCount = item.viewCount,
            subscriberCount = null,
            duration = item.duration,
            uploadDate = uploadDate,
            rawUploadDate = rawUploadDate
        )
    }

    override suspend fun getSearchSuggestions(query: String): List<String> {
        initializer.ensureInitialized()
        return withContext(Dispatchers.IO) {
            try {
                val youtubeService = ServiceList.YouTube
                youtubeService.suggestionExtractor.suggestionList(query)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}
