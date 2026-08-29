
package com.fikriaja.vidly.data.local

import androidx.room.TypeConverter
import com.fikriaja.vidly.domain.model.VideoItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromVideoItemList(value: List<VideoItem>?): String? {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toVideoItemList(value: String?): List<VideoItem>? {
        if (value == null) return null
        return try {
            val listType = object : TypeToken<List<VideoItem>>() {}.type
            gson.fromJson(value, listType)
        } catch (e: Exception) {
            com.fikriaja.vidly.utils.VidlyLog.e("Converters", "Failed to deserialize VideoItem list", e)
            emptyList()
        }
    }

    @TypeConverter
    fun fromMissionStatus(status: MissionStatus): String = status.name

    @TypeConverter
    fun toMissionStatus(name: String): MissionStatus {
        // FIX(LOW): unguarded valueOf crashed every DB read when a stored enum
        // string was unknown (e.g. from a future/older schema). Fall back instead.
        return try {
            MissionStatus.valueOf(name)
        } catch (e: IllegalArgumentException) {
            MissionStatus.QUEUED
        }
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toDownloadStatus(name: String): DownloadStatus {
        return try {
            DownloadStatus.valueOf(name)
        } catch (e: IllegalArgumentException) {
            DownloadStatus.WAITING
        }
    }

    @TypeConverter
    fun fromChunkType(type: ChunkType): String = type.name

    @TypeConverter
    fun toChunkType(name: String): ChunkType {
        return try {
            ChunkType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ChunkType.VIDEO
        }
    }

    @TypeConverter
    fun fromBlacklistType(type: BlacklistType): String = type.name

    @TypeConverter
    fun toBlacklistType(name: String): BlacklistType {
        return try {
            BlacklistType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            BlacklistType.VIDEO
        }
    }
}
