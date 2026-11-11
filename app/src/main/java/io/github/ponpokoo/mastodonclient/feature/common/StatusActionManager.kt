package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.StatusActionField
import io.github.ponpokoo.mastodonclient.domain.session.StatusUpdateKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StatusActionUpdate(
    val session: AccountSession,
    val change: BrowsingSession.Change.StatusUpdated,
)

class PendingStatusAction internal constructor(
    internal val session: AccountSession,
    internal val field: StatusActionField,
    internal val original: TimelineStatus,
    internal val optimistic: TimelineStatus?,
    internal val request: suspend () -> Result<TimelineStatus>,
)

/** Shares in-flight actions across screens displaying the same original post. */
class StatusActionManager(private val repository: TimelineRepository) {
    private data class Key(
        val sessionId: String,
        val instanceUrl: String,
        val accessToken: String,
        val statusId: String,
        val reaction: Boolean,
    )

    private val pending = mutableSetOf<Key>()
    private val mutableUpdates = MutableSharedFlow<StatusActionUpdate>(extraBufferCapacity = 64)
    val updates = mutableUpdates.asSharedFlow()
    private val mutableOptimisticActions = MutableStateFlow<List<StatusActionUpdate>>(emptyList())
    val optimisticActions = mutableOptimisticActions.asStateFlow()

    fun beginFavourite(session: AccountSession, status: TimelineStatus): PendingStatusAction? {
        val enabled = !status.favourited
        val optimistic = status.copy(favourited = enabled, favouritesCount = adjustedCount(status.favouritesCount, enabled))
        return begin(session, status, StatusActionField.Favourite, optimistic) {
            repository.setFavourite(session, status.statusId, enabled)
        }
    }

    fun beginReblog(session: AccountSession, status: TimelineStatus): PendingStatusAction? {
        val enabled = !status.reblogged
        val optimistic = status.copy(reblogged = enabled, boostsCount = adjustedCount(status.boostsCount, enabled))
        return begin(session, status, StatusActionField.Reblog, optimistic) {
            repository.setReblogged(session, status.statusId, enabled)
        }
    }

    fun beginReaction(session: AccountSession, status: TimelineStatus, emoji: String?): PendingStatusAction? =
        begin(session, status, StatusActionField.Reaction, null) {
            repository.setFedibirdReaction(session, status.statusId, emoji)
        }

    suspend fun complete(action: PendingStatusAction, shouldPublish: () -> Boolean = { true }): Result<TimelineStatus> {
        val key = action.session.key(action.original.statusId, reaction = action.field == StatusActionField.Reaction)
        try {
            val result = try {
                action.request().also { currentCoroutineContext().ensureActive() }
            } catch (error: CancellationException) {
                if (shouldPublish()) rollback(action)
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (shouldPublish()) {
                result.fold(
                    onSuccess = { emit(action, it, StatusUpdateKind.Stored) },
                    onFailure = { rollback(action) },
                )
            }
            return result
        } finally {
            if (action.optimistic != null) mutableOptimisticActions.update { updates ->
                updates.filterNot { it.session.key(it.change.status.statusId) == key }
            }
            synchronized(pending) { pending.remove(key) }
        }
    }

    private fun begin(
        session: AccountSession,
        original: TimelineStatus,
        field: StatusActionField,
        optimistic: TimelineStatus?,
        request: suspend () -> Result<TimelineStatus>,
    ): PendingStatusAction? {
        val action = PendingStatusAction(session, field, original, optimistic, request)
        val key = session.key(original.statusId, reaction = field == StatusActionField.Reaction)
        if (!synchronized(pending) { pending.add(key) }) return null
        if (optimistic != null) emit(action, optimistic, StatusUpdateKind.Optimistic)
        return action
    }

    private fun rollback(action: PendingStatusAction) {
        if (action.optimistic != null) emit(action, action.original, StatusUpdateKind.RolledBack)
    }

    private fun emit(action: PendingStatusAction, status: TimelineStatus, kind: StatusUpdateKind) {
        val update = StatusActionUpdate(action.session, BrowsingSession.Change.StatusUpdated(status, action.field, kind))
        if (kind == StatusUpdateKind.Optimistic) mutableOptimisticActions.update { it + update }
        mutableUpdates.tryEmit(update)
    }

    private fun AccountSession.key(statusId: String, reaction: Boolean = false) = Key(sessionId, instanceUrl, accessToken, statusId, reaction)

    private fun adjustedCount(current: Long, enabled: Boolean) =
        (current + if (enabled) 1L else -1L).coerceAtLeast(0L)
}

fun TimelineStatus.withStatusActionUpdate(update: StatusActionUpdate): TimelineStatus = update.change.applyTo(this)
