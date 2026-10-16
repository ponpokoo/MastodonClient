package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.withTransaction
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.applyHomeChange
import io.github.ponpokoo.mastodonclient.domain.session.applyHomeWindowChange
import io.github.ponpokoo.mastodonclient.domain.session.boundHomeWindow
import io.github.ponpokoo.mastodonclient.domain.session.homeIndex
import io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineWindowRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface HomeTimelineLocalDataSource {
    suspend fun protectViewport(session: AccountSession, anchorId: String) = Unit
    suspend fun readWindow(session: AccountSession): TimelinePage? = null
    suspend fun writeWindow(session: AccountSession, page: TimelinePage, anchorId: String?, changes: List<BrowsingSession.Change>) = Unit
    suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) = Unit
    suspend fun readPage(session: AccountSession, maxId: String?, limit: Int, anchorId: String? = null): TimelinePage?
    suspend fun writePage(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<BrowsingSession.Change> = emptyList())
    suspend fun applyChange(session: AccountSession, change: BrowsingSession.Change)
    suspend fun deleteAccount(sessionId: String)
}

/** Bounded server-ordered rows; persisted boundaries separate disconnected windows. */
class RoomHomeTimelineLocalDataSource(
    private val database: BrowsingDatabase,
    private val isAccountPresent: suspend (AccountSession) -> Boolean,
) : HomeTimelineLocalDataSource {
    private val dao = database.homeTimeline()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writes = Mutex()
    private var moderation = io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState()

    override suspend fun protectViewport(session: AccountSession, anchorId: String) = writes.withLock {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (!isAccountPresent(session)) return@withTransaction
                val instance = session.instanceUrl.trimEnd('/')
                val record = dao.window(session.sessionId, instance)?.let(::decodeWindow) ?: return@withTransaction
                if (record.anchorId != anchorId) dao.writeWindow(HomeTimelineWindowEntity(session.sessionId, instance,
                    json.encodeToString(record.copy(anchorId = anchorId))))
            }
        }
    }

    override suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) =
        mutate(session) { previous -> moderation = state; previous }

    override suspend fun readWindow(session: AccountSession): TimelinePage? = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction null
            val instance = session.instanceUrl.trimEnd('/')
            val state = dao.state(session.sessionId, instance) ?: return@withTransaction null
            val rows = dao.readAll(session.sessionId, instance)
            val statuses = rows.map { decode(it) ?: return@withTransaction null }
            val stored = dao.window(session.sessionId, instance)
            val record = stored?.let { decodeWindow(it) ?: return@withTransaction null }
            TimelinePage(statuses, record?.nextMaxId ?: statuses.lastOrNull()?.timelineId,
                record?.endReached ?: state.endReached, record?.index?.copy(streamBoundary = true))
        }
    }

    override suspend fun writeWindow(session: AccountSession, page: TimelinePage, anchorId: String?, changes: List<BrowsingSession.Change>) =
        mutate(session, anchorId) { changes.fold(page.copy(homeIndex = page.homeIndex())) { window, change -> window.applyHomeWindowChange(change) } }

    override suspend fun readPage(session: AccountSession, maxId: String?, limit: Int, anchorId: String?): TimelinePage? = withContext(Dispatchers.IO) {
        require(limit in 1..40)
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction null
            val instance = session.instanceUrl.trimEnd('/')
            val state = dao.state(session.sessionId, instance) ?: return@withTransaction null
            val storedWindow = dao.window(session.sessionId, instance)
            if (storedWindow != null) {
                val record = decodeWindow(storedWindow) ?: return@withTransaction null
                val ids = record.index.ids
                val start = when {
                    anchorId != null -> ids.indexOf(anchorId)
                    maxId == null -> 0
                    else -> ids.indexOf(maxId).takeIf { it >= 0 }?.plus(1) ?: -1
                }
                if (start < 0) return@withTransaction null
                val boundary = record.index.gaps.map { ids.indexOf(it.beforeId) }.filter { it >= start }.minOrNull() ?: ids.size
                if (boundary == start) return@withTransaction null
                val slice = ids.drop(start).take(minOf(limit, boundary - start))
                if (slice.isEmpty() && !record.endReached) return@withTransaction null
                val rows = dao.readAll(session.sessionId, instance).associateBy { it.id }
                val statuses = slice.mapNotNull { id -> rows[id]?.let { decode(it) ?: return@withTransaction null } }
                return@withTransaction TimelinePage(statuses, slice.lastOrNull(), record.endReached && start + slice.size == ids.size)
            }
            val after = if (anchorId != null) (dao.position(session.sessionId, instance, anchorId) ?: return@withTransaction null) - 1
                else if (maxId == null) -1 else dao.position(session.sessionId, instance, maxId) ?: return@withTransaction null
            val rows = dao.readPage(session.sessionId, instance, after, limit)
            // A missing/incompatible row is a gap, so let the server supply this page.
            val statuses = rows.map { decode(it) ?: return@withTransaction null }
            if (rows.isEmpty() && !state.endReached) return@withTransaction null
            TimelinePage(statuses, statuses.lastOrNull()?.timelineId,
                state.endReached && (rows.lastOrNull()?.position ?: after) >= (dao.lastPosition(session.sessionId, instance) ?: -1))
        }
    }

    override suspend fun writePage(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<BrowsingSession.Change>) = mutate(session) { previous ->
        val cursorIndex = if (maxId == null) -1 else previous.statuses.indexOfFirst { it.timelineId == maxId }
        // A restored viewport outside the saved window must not overwrite the latest window.
        if (maxId != null && cursorIndex < 0) return@mutate previous
        val fresh = page.statuses.distinctBy(TimelineStatus::timelineId)
        val lastIndex = fresh.lastOrNull()?.let { last -> previous.statuses.indexOfFirst { it.timelineId == last.timelineId } } ?: -1
        val hasTail = !page.endReached && lastIndex > cursorIndex
        val tail = if (hasTail) previous.statuses.drop(lastIndex + 1) else emptyList()
        val merged = (previous.statuses.take(cursorIndex + 1) + fresh + tail).distinctBy(TimelineStatus::timelineId)
        TimelinePage(changes.fold(merged) { rows, change -> rows.applyHomeChange(change, includeNew = true) },
            merged.lastOrNull()?.timelineId, if (hasTail) previous.endReached else page.endReached)
    }

    override suspend fun applyChange(session: AccountSession, change: BrowsingSession.Change) = mutate(session) { previous ->
        if (previous.homeIndex != null) previous.applyHomeWindowChange(change)
        else previous.copy(statuses = previous.statuses.applyHomeChange(change, includeNew = true))
    }

    private suspend fun mutate(session: AccountSession, anchorId: String? = null, transform: (TimelinePage) -> TimelinePage) = writes.withLock {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (!isAccountPresent(session)) return@withTransaction
                currentCoroutineContext().ensureActive()
                val instance = session.instanceUrl.trimEnd('/')
                val rows = dao.readAll(session.sessionId, instance)
                val storedWindow = dao.window(session.sessionId, instance)
                val record = storedWindow?.let(::decodeWindow)
                // Stop at the first damaged row rather than silently skipping a gap.
                val decoded = rows.map(::decode).takeWhile { it != null }.filterNotNull()
                val intact = storedWindow == null || (record != null && decoded.size == rows.size)
                val readable = if (intact) decoded else emptyList()
                val previous = TimelinePage(readable, if (intact) record?.nextMaxId ?: readable.lastOrNull()?.timelineId else null,
                    intact && decoded.size == rows.size && dao.state(session.sessionId, instance)?.endReached == true,
                    record?.index.takeIf { intact })
                val transformed = transform(previous)
                val protectedAnchor = anchorId ?: record?.anchorId
                val result = if (transformed.homeIndex != null) transformed.boundHomeWindow(protectedAnchor, MAX_HOME_STATUSES) else transformed
                val statuses = result.statuses.filterNot { moderation.hides(session, it) }.take(MAX_HOME_STATUSES)
                dao.clearRows(session.sessionId, instance)
                dao.insert(statuses.mapIndexed { position, status ->
                    CachedHomeStatusEntity(session.sessionId, instance, status.timelineId, position, json.encodeToString(status))
                })
                dao.writeState(HomeTimelineStateEntity(session.sessionId, instance,
                    result.endReached && result.statuses.size <= MAX_HOME_STATUSES))
                if (result.homeIndex != null) dao.writeWindow(HomeTimelineWindowEntity(session.sessionId, instance,
                    json.encodeToString(HomeTimelineWindowRecord(result.homeIndex, result.nextMaxId, result.endReached, protectedAnchor))))
                else dao.clearWindow(session.sessionId, instance)
            }
        }
    }

    private fun decode(row: CachedHomeStatusEntity): TimelineStatus? = try {
        json.decodeFromString<TimelineStatus>(row.payload)
    } catch (_: SerializationException) { null }

    private fun decodeWindow(row: HomeTimelineWindowEntity): HomeTimelineWindowRecord? = try {
        json.decodeFromString<HomeTimelineWindowRecord>(row.payload)
    } catch (_: SerializationException) { null }

    override suspend fun deleteAccount(sessionId: String) = writes.withLock {
        database.withTransaction { dao.deleteRows(sessionId); dao.deleteState(sessionId); dao.deleteWindows(sessionId) }
    }

    companion object { const val MAX_HOME_STATUSES = 500 }
}
