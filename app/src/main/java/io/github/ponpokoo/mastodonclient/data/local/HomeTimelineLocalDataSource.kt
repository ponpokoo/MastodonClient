package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.withTransaction
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.applyHomeChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface HomeTimelineLocalDataSource {
    suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) = Unit
    suspend fun readPage(session: AccountSession, maxId: String?, limit: Int, anchorId: String? = null): TimelinePage?
    suspend fun writePage(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<BrowsingSession.Change> = emptyList())
    suspend fun applyChange(session: AccountSession, change: BrowsingSession.Change)
    suspend fun deleteAccount(sessionId: String)
}

/** One bounded, contiguous server-ordered window per account; unknown gaps are never joined. */
class RoomHomeTimelineLocalDataSource(
    private val database: BrowsingDatabase,
    private val isAccountPresent: suspend (AccountSession) -> Boolean,
) : HomeTimelineLocalDataSource {
    private val dao = database.homeTimeline()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writes = Mutex()
    private var moderation = io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState()

    override suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) =
        mutate(session) { previous -> moderation = state; previous }

    override suspend fun readPage(session: AccountSession, maxId: String?, limit: Int, anchorId: String?): TimelinePage? = withContext(Dispatchers.IO) {
        require(limit in 1..40)
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction null
            val instance = session.instanceUrl.trimEnd('/')
            val state = dao.state(session.sessionId, instance) ?: return@withTransaction null
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
        previous.copy(statuses = previous.statuses.applyHomeChange(change, includeNew = true))
    }

    private suspend fun mutate(session: AccountSession, transform: (TimelinePage) -> TimelinePage) = writes.withLock {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (!isAccountPresent(session)) return@withTransaction
                currentCoroutineContext().ensureActive()
                val instance = session.instanceUrl.trimEnd('/')
                val rows = dao.readAll(session.sessionId, instance)
                // Stop at the first damaged row rather than silently skipping a gap.
                val decoded = rows.map(::decode).takeWhile { it != null }.filterNotNull()
                val previous = TimelinePage(decoded, decoded.lastOrNull()?.timelineId,
                    decoded.size == rows.size && dao.state(session.sessionId, instance)?.endReached == true)
                val result = transform(previous)
                val statuses = result.statuses.filterNot { moderation.hides(session, it) }.take(MAX_HOME_STATUSES)
                dao.clearRows(session.sessionId, instance)
                dao.insert(statuses.mapIndexed { position, status ->
                    CachedHomeStatusEntity(session.sessionId, instance, status.timelineId, position, json.encodeToString(status))
                })
                dao.writeState(HomeTimelineStateEntity(session.sessionId, instance,
                    result.endReached && result.statuses.size <= MAX_HOME_STATUSES))
            }
        }
    }

    private fun decode(row: CachedHomeStatusEntity): TimelineStatus? = try {
        json.decodeFromString<TimelineStatus>(row.payload)
    } catch (_: SerializationException) { null }

    override suspend fun deleteAccount(sessionId: String) = writes.withLock {
        database.withTransaction { dao.deleteRows(sessionId); dao.deleteState(sessionId) }
    }

    companion object { const val MAX_HOME_STATUSES = 500 }
}
