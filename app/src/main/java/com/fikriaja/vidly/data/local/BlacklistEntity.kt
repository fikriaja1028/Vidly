
package com.fikriaja.vidly.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklist")
data class BlacklistEntity(
    @PrimaryKey
    val id: String, // Video or Channel ID
    val type: BlacklistType,
    val timestamp: Long = System.currentTimeMillis()
)

enum class BlacklistType {
    VIDEO,
    CHANNEL
}
