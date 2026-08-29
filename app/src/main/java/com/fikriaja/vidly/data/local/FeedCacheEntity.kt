
package com.fikriaja.vidly.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fikriaja.vidly.domain.model.VideoItem

@Entity(tableName = "feed_cache")
data class FeedCacheEntity(
    @PrimaryKey val feedKey: String, // e.g., "home_trending", "subs_all", "subs_channel_<id>"
    val videos: List<VideoItem>,
    val timestamp: Long = System.currentTimeMillis()
)
