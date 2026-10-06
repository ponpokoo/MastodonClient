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

@Entity(tableName = "notification_category_cache", primaryKeys = ["sessionId", "instanceUrl", "category"])
data class NotificationCategoryCacheEntity(
    val sessionId: String, val instanceUrl: String, val category: String, val payload: String,
)

@Entity(tableName = "notification_read_state", primaryKeys = ["sessionId", "instanceUrl"])
data class NotificationReadStateEntity(
    val sessionId: String, val instanceUrl: String, val payload: String,
)

@Dao
interface NotificationCacheDao {
    @Query("SELECT payload FROM notification_category_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND category = :category")
    suspend fun readCategory(sessionId: String, instanceUrl: String, category: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeCategory(row: NotificationCategoryCacheEntity)

    @Query("SELECT payload FROM notification_read_state WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun readReadState(sessionId: String, instanceUrl: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeReadState(row: NotificationReadStateEntity)

    @Query("DELETE FROM notification_category_cache WHERE sessionId = :sessionId")
    suspend fun deleteCategories(sessionId: String)

    @Query("DELETE FROM notification_read_state WHERE sessionId = :sessionId")
    suspend fun deleteReadState(sessionId: String)
    @Query("DELETE FROM notification_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND id IN (:ids)")
    suspend fun deleteRows(sessionId: String, instanceUrl: String, ids: List<String>)
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
