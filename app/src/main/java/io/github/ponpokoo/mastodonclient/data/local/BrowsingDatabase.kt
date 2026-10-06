package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Shared database boundary; future timelines can add tables through versioned migrations. */
@Database(entities = [CachedNotificationEntity::class, NotificationMarkerEntity::class,
    CachedHomeStatusEntity::class, HomeTimelineStateEntity::class,
    NotificationCategoryCacheEntity::class, NotificationReadStateEntity::class], version = 3)
abstract class BrowsingDatabase : RoomDatabase() {
    abstract fun notifications(): NotificationCacheDao
    abstract fun homeTimeline(): HomeTimelineCacheDao

    companion object {
        @Volatile private var instance: BrowsingDatabase? = null
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS home_timeline_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS home_timeline_state (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, endReached INTEGER NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_category_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, category TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, category))")
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_read_state (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            }
        }

        fun get(context: Context): BrowsingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, BrowsingDatabase::class.java, "browsing.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
