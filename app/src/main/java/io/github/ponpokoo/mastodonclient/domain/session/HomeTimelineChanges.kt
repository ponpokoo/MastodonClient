package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent

internal fun TimelineStatus.applyHomeStatusUpdate(change: BrowsingSession.Change.StatusUpdated): TimelineStatus {
    if (statusId != change.status.statusId) return this
    val updated = change.applyTo(this)
    return if (change.field == null && updated.poll == null) updated.copy(poll = poll) else updated
}

/** Keep each boost row's identity and chronology when changing the underlying status. */
fun List<TimelineStatus>.applyHomeChange(change: BrowsingSession.Change, includeNew: Boolean = false): List<TimelineStatus> = when (change) {
    is BrowsingSession.Change.TagUpdated, is BrowsingSession.Change.ProfileUpdated -> this
    is BrowsingSession.Change.StatusUpdated -> map { it.applyHomeStatusUpdate(change) }
    is BrowsingSession.Change.StatusDeleted -> filterNot { it.statusId == change.statusId }
    is BrowsingSession.Change.Stream -> when (val event = change.event) {
        is TimelineStreamEvent.StatusDeleted -> filterNot { it.statusId == event.statusId || it.timelineId == event.statusId }
        is TimelineStreamEvent.StatusAdded -> when {
            event.isEdit -> map {
                if (it.statusId == event.status.statusId) event.status.copy(timelineId = it.timelineId, boostedBy = it.boostedBy, createdAt = it.createdAt) else it
            }
            any { it.timelineId == event.status.timelineId } -> map { if (it.timelineId == event.status.timelineId) event.status else it }
            includeNew -> listOf(event.status) + this
            else -> this
        }
        is TimelineStreamEvent.FiltersChanged, is TimelineStreamEvent.Connected -> this
        is TimelineStreamEvent.NotificationReceived -> this
    }
}
