package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Shared database boundary; future timelines can add tables through versioned migrations. */
@Database(entities = [CachedNotificationEntity::class, NotificationMarkerEntity::class], version = 1)
abstract class BrowsingDatabase : RoomDatabase() {
    abstract fun notifications(): NotificationCacheDao

    companion object {
        @Volatile private var instance: BrowsingDatabase? = null

        fun get(context: Context): BrowsingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, BrowsingDatabase::class.java, "browsing.db",
            ).build().also { instance = it }
        }
    }
}
