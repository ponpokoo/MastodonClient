package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Keep opaque IDs and the server/list order. No numeric ID conversion or lexical ID sorting.
@Entity(tableName = "notification_cache", primaryKeys = ["sessionId", "instanceUrl", "id"])
data class CachedNotificationEntity(
    val sessionId: String,
    val instanceUrl: String,
    val id: String,
    val position: Int,
    val payload: String,
)

@Entity(tableName = "notification_markers", primaryKeys = ["sessionId", "instanceUrl"])
data class NotificationMarkerEntity(
    val sessionId: String,
    val instanceUrl: String,
    val lastReadId: String?,
)

@Dao
interface NotificationCacheDao {
    @Query("SELECT * FROM notification_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl ORDER BY position")
    suspend fun read(sessionId: String, instanceUrl: String): List<CachedNotificationEntity>

    @Query("SELECT lastReadId FROM notification_markers WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun readMarker(sessionId: String, instanceUrl: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<CachedNotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeMarker(marker: NotificationMarkerEntity)

    @Query("DELETE FROM notification_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun clearRows(sessionId: String, instanceUrl: String)

    @Query("DELETE FROM notification_cache WHERE sessionId = :sessionId")
    suspend fun deleteNotifications(sessionId: String)

    @Query("DELETE FROM notification_markers WHERE sessionId = :sessionId")
    suspend fun deleteMarker(sessionId: String)
}
