package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Entity(
    tableName = "home_timeline_cache",
    primaryKeys = ["sessionId", "instanceUrl", "id"],
    indices = [Index(value = ["sessionId", "instanceUrl", "originalId"])],
)
data class CachedHomeStatusEntity(
    val sessionId: String,
    val instanceUrl: String,
    val id: String,
    val position: Int,
    val payload: String,
    val originalId: String?,
)

@Entity(tableName = "home_timeline_state", primaryKeys = ["sessionId", "instanceUrl"])
data class HomeTimelineStateEntity(val sessionId: String, val instanceUrl: String, val endReached: Boolean)

@Entity(tableName = "home_timeline_windows", primaryKeys = ["sessionId", "instanceUrl"])
data class HomeTimelineWindowEntity(val sessionId: String, val instanceUrl: String, val payload: String)

@Dao
interface HomeTimelineCacheDao {
    @Query("SELECT * FROM home_timeline_windows WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun window(sessionId: String, instanceUrl: String): HomeTimelineWindowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeWindow(window: HomeTimelineWindowEntity)

    @Query("DELETE FROM home_timeline_windows WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl")
    suspend fun clearWindow(sessionId: String, instanceUrl: String)

    @Query("DELETE FROM home_timeline_windows WHERE sessionId = :sessionId")
    suspend fun deleteWindows(sessionId: String)
    @Query("SELECT * FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl ORDER BY position")
    suspend fun readAll(sessionId: String, instanceUrl: String): List<CachedHomeStatusEntity>

    @Query("SELECT * FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND id IN (:ids)")
    suspend fun readRows(sessionId: String, instanceUrl: String, ids: List<String>): List<CachedHomeStatusEntity>

    @Query("SELECT * FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND originalId IN (:ids) ORDER BY position")
    suspend fun readByOriginalIds(sessionId: String, instanceUrl: String, ids: List<String>): List<CachedHomeStatusEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND originalId IS NULL)")
    suspend fun hasUnindexedRows(sessionId: String, instanceUrl: String): Boolean

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

    @Update
    suspend fun update(rows: List<CachedHomeStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeState(state: HomeTimelineStateEntity)

    @Query("UPDATE home_timeline_cache SET position = position + :offset WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND id IN (:ids)")
    suspend fun moveRows(sessionId: String, instanceUrl: String, ids: List<String>, offset: Int)

    @Query("UPDATE home_timeline_cache SET position = position + :offset WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND position > :after AND position < :before")
    suspend fun shiftPositions(sessionId: String, instanceUrl: String, after: Int, before: Int, offset: Int)

    @Query("DELETE FROM home_timeline_cache WHERE sessionId = :sessionId AND instanceUrl = :instanceUrl AND id IN (:ids)")
    suspend fun deleteByIds(sessionId: String, instanceUrl: String, ids: List<String>)

    @Query("DELETE FROM home_timeline_cache WHERE sessionId = :sessionId")
    suspend fun deleteRows(sessionId: String)

    @Query("DELETE FROM home_timeline_state WHERE sessionId = :sessionId")
    suspend fun deleteState(sessionId: String)
}
