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
        data class Stream(val event: TimelineStreamEvent) : Change
        data class StatusUpdated(val status: TimelineStatus) : Change
        data class StatusDeleted(val statusId: String) : Change
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events = mutableEvents.asSharedFlow()

    fun activate(account: AccountSession?) {
        mutableSnapshot.value = Snapshot(account, mutableSnapshot.value.generation + 1)
    }

    suspend fun publish(snapshot: Snapshot, change: Change) {
        if (this.snapshot.value == snapshot) mutableEvents.emit(Event(snapshot, change))
    }
}

/** Keep boost context/identity when updating a status displayed in multiple lists. */
fun TimelineStatus.withUpdatedActions(updated: TimelineStatus): TimelineStatus =
    if (statusId != updated.statusId) this else copy(
        repliesCount = updated.repliesCount,
        boostsCount = updated.boostsCount,
        favouritesCount = updated.favouritesCount,
        favourited = updated.favourited,
        reblogged = updated.reblogged,
        bookmarked = updated.bookmarked,
        pinned = updated.pinned,
        reactions = updated.reactions,
    )
