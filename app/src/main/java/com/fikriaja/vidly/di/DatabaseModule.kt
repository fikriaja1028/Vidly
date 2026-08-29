/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fikriaja.vidly.data.local.DownloadDao
import com.fikriaja.vidly.data.local.FavoriteDao
import com.fikriaja.vidly.data.local.HistoryDao
import com.fikriaja.vidly.data.local.VidlyDatabase
import com.fikriaja.vidly.data.local.PlaylistFavoriteDao
import com.fikriaja.vidly.data.local.SearchHistoryDao
import com.fikriaja.vidly.data.local.SubscriptionDao
import com.fikriaja.vidly.data.local.UserInterestDao
import com.fikriaja.vidly.data.local.BlacklistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VidlyDatabase {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blacklist` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        return Room.databaseBuilder(
            context,
            VidlyDatabase::class.java,
            "vidly_db"
        )
        .addMigrations(MIGRATION_9_10)
        // FIX(BUG #2): Removed blanket fallbackToDestructiveMigration() â€” it silently
        // WIPED all user data (history, favorites, subscriptions, playlists, interests)
        // whenever a migration path was missing. Downgrades remain destructive (unsupported),
        // but future schema upgrades MUST now ship explicit migrations instead of nuking data.
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    }

    @Provides
    fun provideDownloadDao(database: VidlyDatabase): DownloadDao {
        return database.downloadDao()
    }

    @Provides
    fun provideHistoryDao(database: VidlyDatabase): HistoryDao {
        return database.historyDao()
    }

    @Provides
    fun provideFavoriteDao(database: VidlyDatabase): FavoriteDao {
        return database.favoriteDao()
    }

    @Provides
    fun providePlaylistFavoriteDao(database: VidlyDatabase): PlaylistFavoriteDao {
        return database.playlistFavoriteDao()
    }

    @Provides
    fun provideSubscriptionDao(database: VidlyDatabase): SubscriptionDao {
        return database.subscriptionDao()
    }

    @Provides
    fun provideSearchHistoryDao(database: VidlyDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }

    @Provides
    fun provideUserInterestDao(database: VidlyDatabase): UserInterestDao {
        return database.userInterestDao()
    }

    @Provides
    fun provideBlacklistDao(database: VidlyDatabase): BlacklistDao {
        return database.blacklistDao()
    }

    @Provides
    fun provideLocalPlaylistDao(database: VidlyDatabase): com.fikriaja.vidly.data.local.LocalPlaylistDao {
        return database.localPlaylistDao()
    }

    @Provides
    fun provideFeedCacheDao(database: VidlyDatabase): com.fikriaja.vidly.data.local.FeedCacheDao {
        return database.feedCacheDao()
    }

    @Provides
    fun provideMissionDao(database: VidlyDatabase): com.fikriaja.vidly.data.local.MissionDao {
        return database.missionDao()
    }
}
