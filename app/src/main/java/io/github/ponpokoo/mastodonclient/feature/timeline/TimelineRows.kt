package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineGap
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus

internal sealed interface TimelineRow {
    val key: Any
    data class Post(val status: TimelineStatus) : TimelineRow { override val key get() = status.timelineId }
    data class Gap(val gap: HomeTimelineGap) : TimelineRow { override val key: Any get() = GapKey(gap.beforeId) }
}

private data class GapKey(val id: String) : java.io.Serializable

internal fun TimelineUiState.displayRows(): List<TimelineRow> {
    val index = homeIndex ?: return statuses.map(TimelineRow::Post)
    val posts = statuses.associateBy { it.timelineId }
    val gaps = index.gaps.associateBy { it.beforeId }
    return buildList {
        for (id in index.ids) {
            gaps[id]?.takeUnless { it.beforeId == automaticGapCheck?.beforeId }
                ?.let { add(TimelineRow.Gap(it)) }
            posts[id]?.let { add(TimelineRow.Post(it)) }
        }
        // A newly restored anchor can precede the next server page.
        statuses.filter { it.timelineId !in index.ids }.forEach { add(TimelineRow.Post(it)) }
    }
}
