package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "home_timeline_cache", primaryKeys = ["sessionId", "instanceUrl", "id"])
data class CachedHomeStatusEntity(
    val sessionId: String,
    val instanceUrl: String,
    val id: String,
    val position: Int,
    val payload: String,
)

@Entity(tableName = "home_timeline_state", primaryKeys = ["sessionId", "instanceUrl"])
data class HomeTimelineStateEntity(val sessionId: String, val instanceUrl: String, val endReached: Boolean)

@Dao
interface HomeTimelineCacheDao {
    @Query("SELECT * FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl ORDER BY position")
    suspend fun readAll(sessionId: String, instanceUrl: String): List<CachedHomeStatusEntity>

    @Query("SELECT * FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND position > :after ORDER BY position LIMIT :limit")
    suspend fun readPage(sessionId: String, instanceUrl: String, after: Int, limit: Int): List<CachedHomeStatusEntity>

    @Query("SELECT position FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND id = :id")
    suspend fun position(sessionId: String, instanceUrl: String, id: String): Int?

    @Query("SELECT * FROM home_timeline_state WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun state(sessionId: String, instanceUrl: String): HomeTimelineStateEntity?

    @Query("SELECT MAX(position) FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun lastPosition(sessionId: String, instanceUrl: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<CachedHomeStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeState(state: HomeTimelineStateEntity)

    @Query("DELETE FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun clearRows(sessionId: String, instanceUrl: String)

    @Query("DELETE FROM home_timeline_cache WHERE sessionId = :sessionId")
    suspend fun deleteRows(sessionId: String)

    @Query("DELETE FROM home_timeline_state WHERE sessionId = :sessionId")
    suspend fun deleteState(sessionId: String)
}
