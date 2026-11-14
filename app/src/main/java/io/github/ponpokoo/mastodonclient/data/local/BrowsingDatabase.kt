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
    NotificationCategoryCacheEntity::class, NotificationReadStateEntity::class,
    HomeTimelineWindowEntity::class], version = 5)
abstract class BrowsingDatabase : RoomDatabase() {
    abstract fun notifications(): NotificationCacheDao
    abstract fun homeTimeline(): HomeTimelineCacheDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE home_timeline_cache ADD COLUMN originalId TEXT")
                db.compileStatement("UPDATE home_timeline_cache SET originalId = ? WHERE rowid = ?").use { update ->
                    var after = Long.MIN_VALUE
                    while (true) {
                        val batch = db.query(
                            "SELECT rowid, payload FROM home_timeline_cache WHERE rowid > ? ORDER BY rowid LIMIT 100",
                            arrayOf(after),
                        ).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    add(cursor.getLong(0) to decodeHomeStatus(cursor.getString(1))?.statusId)
                                }
                            }
                        }
                        if (batch.isEmpty()) break
                        for ((rowId, originalId) in batch) {
                            // A damaged payload remains available to the normal cache recovery path.
                            if (originalId == null) continue
                            update.bindString(1, originalId)
                            update.bindLong(2, rowId)
                            update.executeUpdateDelete()
                        }
                        after = batch.last().first
                    }
                }
                db.execSQL("CREATE INDEX index_home_timeline_cache_sessionId_instanceUrl_originalId ON home_timeline_cache (sessionId, instanceUrl, originalId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS home_timeline_windows (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            }
        }
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
