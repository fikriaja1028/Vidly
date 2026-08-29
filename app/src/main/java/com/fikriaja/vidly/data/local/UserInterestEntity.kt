
package com.fikriaja.vidly.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_interests")
data class UserInterestEntity(
    @PrimaryKey val keyword: String,
    val weight: Float,
    val lastUpdated: Long = System.currentTimeMillis()
)
