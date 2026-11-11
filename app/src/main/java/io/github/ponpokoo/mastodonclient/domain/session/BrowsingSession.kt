package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** One browsing session per main navigation entry, shared by its four tabs. */
class BrowsingSession {
    data class Snapshot(val account: AccountSession? = null, val generation: Long = 0)
    data class Event(val snapshot: Snapshot, val change: Change)
    sealed interface Change {
        data class ProfileUpdated(val profile: io.github.ponpokoo.mastodonclient.domain.model.UserProfile) : Change
        data class TagUpdated(val tag: io.github.ponpokoo.mastodonclient.domain.model.SearchTag) : Change
        data class Stream(val event: TimelineStreamEvent) : Change
        data class StatusUpdated(
            val status: TimelineStatus,
            val field: StatusActionField? = null,
            val kind: StatusUpdateKind = StatusUpdateKind.Confirmed,
        ) : Change {
            fun applyTo(displayed: TimelineStatus): TimelineStatus = displayed.withUpdatedActions(status, field)
        }
        data class StatusDeleted(val statusId: String) : Change
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events = mutableEvents.asSharedFlow()
    private val mutableOptimisticActions = MutableStateFlow<Map<String, Change.StatusUpdated>>(emptyMap())
    val optimisticActions = mutableOptimisticActions.asStateFlow()

    fun activate(account: AccountSession?) {
        mutableOptimisticActions.value = emptyMap()
        mutableSnapshot.value = Snapshot(account, mutableSnapshot.value.generation + 1)
    }

    suspend fun publish(snapshot: Snapshot, change: Change) {
        if (this.snapshot.value != snapshot) return
        if (change is Change.StatusUpdated && change.kind == StatusUpdateKind.Optimistic) {
            mutableOptimisticActions.value += change.status.statusId to change
        }
        mutableEvents.emit(Event(snapshot, change))
        if (this.snapshot.value == snapshot && change is Change.StatusUpdated && change.field != null &&
            change.kind != StatusUpdateKind.Optimistic) {
            val id = change.status.statusId
            if (mutableOptimisticActions.value[id]?.field == change.field) mutableOptimisticActions.value -= id
        }
    }
}

/** Stored updates have already been handled by the repository's Home cache write. */
enum class StatusUpdateKind { Confirmed, Optimistic, RolledBack, Stored }

enum class StatusActionField { Favourite, Reblog, Reaction, Bookmark, Pin, Poll }

/** Keep boost context/identity when updating a status displayed in multiple lists. */
fun TimelineStatus.withUpdatedActions(
    updated: TimelineStatus,
    field: StatusActionField? = null,
): TimelineStatus = if (statusId != updated.statusId) this else when (field) {
    StatusActionField.Favourite -> copy(favourited = updated.favourited, favouritesCount = updated.favouritesCount)
    StatusActionField.Reblog -> copy(reblogged = updated.reblogged, boostsCount = updated.boostsCount)
    StatusActionField.Reaction -> copy(reactions = updated.reactions)
    StatusActionField.Bookmark -> copy(bookmarked = updated.bookmarked)
    StatusActionField.Pin -> copy(pinned = updated.pinned)
    StatusActionField.Poll -> copy(poll = updated.poll)
    null -> copy(
        repliesCount = updated.repliesCount,
        boostsCount = updated.boostsCount,
        favouritesCount = updated.favouritesCount,
        favourited = updated.favourited,
        reblogged = updated.reblogged,
        bookmarked = updated.bookmarked,
        pinned = updated.pinned,
        reactions = updated.reactions,
        poll = updated.poll,
    )
}
