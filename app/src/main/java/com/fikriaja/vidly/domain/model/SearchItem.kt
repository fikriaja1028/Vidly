/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.annotation.Keep

@Keep
@Stable
sealed interface SearchItem {
    val uniqueKey: String

    @Keep
    @Immutable
    data class Video(val video: VideoItem) : SearchItem {
        override val uniqueKey: String get() = "video_${video.id}"
    }

    @Keep
    @Immutable
    data class Channel(
        val id: String,
        val name: String,
        val thumbnailUrl: String?,
        val subscriberCount: Long?,
        val description: String?,
        val isSubscribed: Boolean = false
    ) : SearchItem {
        override val uniqueKey: String get() = "channel_$id"
    }

    @Keep
    @Immutable
    data class Playlist(val playlist: PlaylistItem) : SearchItem {
        override val uniqueKey: String get() = "playlist_${playlist.id}"
    }
}
