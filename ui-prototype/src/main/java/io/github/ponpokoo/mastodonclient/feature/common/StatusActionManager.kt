package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class OptimisticStatusAction { Favourite, Reblog }

data class StatusActionUpdate(
    val sessionId: String,
    val instanceUrl: String,
    val action: OptimisticStatusAction,
    val status: TimelineStatus,
)

class PendingStatusAction internal constructor(
    internal val session: AccountSession,
    internal val action: OptimisticStatusAction,
    internal val original: TimelineStatus,
    val optimistic: TimelineStatus,
)

/** Coordinates optimistic favourite/reblog state across every screen. */
class StatusActionManager(private val repository: TimelineRepository) {
    private data class Key(
        val sessionId: String,
        val instanceUrl: String,
        val statusId: String,
    )

    private val pending = mutableSetOf<Key>()
    private val mutableUpdates = MutableSharedFlow<StatusActionUpdate>(extraBufferCapacity = 64)
    val updates = mutableUpdates.asSharedFlow()

    fun beginFavourite(session: AccountSession, status: TimelineStatus): PendingStatusAction? =
        begin(session, status, OptimisticStatusAction.Favourite)

    fun beginReblog(session: AccountSession, status: TimelineStatus): PendingStatusAction? =
        begin(session, status, OptimisticStatusAction.Reblog)

    suspend fun complete(action: PendingStatusAction): Result<TimelineStatus> {
        val key = action.key()
        return try {
            val result = when (action.action) {
                OptimisticStatusAction.Favourite -> repository.setFavourite(
                    action.session,
                    action.original.statusId,
                    action.optimistic.favourited,
                )
                OptimisticStatusAction.Reblog -> repository.setReblogged(
                    action.session,
                    action.original.statusId,
                    action.optimistic.reblogged,
                )
            }
            emit(action.session, action.action, result.getOrElse { action.original })
            result
        } catch (error: CancellationException) {
            emit(action.session, action.action, action.original)
            throw error
        } catch (error: Throwable) {
            emit(action.session, action.action, action.original)
            Result.failure(error)
        } finally {
            synchronized(pending) { pending.remove(key) }
        }
    }

    private fun begin(
        session: AccountSession,
        status: TimelineStatus,
        action: OptimisticStatusAction,
    ): PendingStatusAction? {
        val key = Key(session.sessionId, session.instanceUrl, status.statusId)
        if (!synchronized(pending) { pending.add(key) }) return null
        val optimistic = when (action) {
            OptimisticStatusAction.Favourite -> status.copy(
                favourited = !status.favourited,
                favouritesCount = adjustedCount(status.favouritesCount, !status.favourited),
            )
            OptimisticStatusAction.Reblog -> status.copy(
                reblogged = !status.reblogged,
                boostsCount = adjustedCount(status.boostsCount, !status.reblogged),
            )
        }
        emit(session, action, optimistic)
        return PendingStatusAction(session, action, status, optimistic)
    }

    private fun emit(session: AccountSession, action: OptimisticStatusAction, status: TimelineStatus) {
        mutableUpdates.tryEmit(StatusActionUpdate(session.sessionId, session.instanceUrl, action, status))
    }

    private fun PendingStatusAction.key() =
        Key(session.sessionId, session.instanceUrl, original.statusId)

    private fun adjustedCount(current: Long, enabled: Boolean) =
        (current + if (enabled) 1L else -1L).coerceAtLeast(0L)
}

fun TimelineStatus.withStatusActionUpdate(update: StatusActionUpdate): TimelineStatus {
    if (statusId != update.status.statusId) return this
    return when (update.action) {
        OptimisticStatusAction.Favourite -> copy(
            favourited = update.status.favourited,
            favouritesCount = update.status.favouritesCount,
        )
        OptimisticStatusAction.Reblog -> copy(
            reblogged = update.status.reblogged,
            boostsCount = update.status.boostsCount,
        )
    }
}
