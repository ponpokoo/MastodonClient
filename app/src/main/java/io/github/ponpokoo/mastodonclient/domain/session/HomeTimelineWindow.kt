package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.*

fun TimelinePage.homeIndex() = homeIndex ?: HomeTimelineIndex(
    (statuses.map { it.timelineId } + listOfNotNull(nextMaxId)).distinct())

/** Merge one server-ordered page. Never infer adjacency from numeric IDs or timestamps. */
fun TimelinePage.mergeHomePage(page: TimelinePage, maxId: String? = null): TimelinePage {
    val old = homeIndex()
    val incoming = page.homeIndex()
    if (incoming.partial) return this
    val fresh = incoming.ids.distinct()
    val cursor = if (maxId == null) -1 else old.ids.indexOf(maxId)
    if (maxId != null && cursor < 0) return this
    val start = cursor + 1
    val remaining = old.ids.drop(start)
    // The last overlap defines the already traversed part, including any older gaps crossed.
    val overlap = fresh.map { remaining.indexOf(it) }.maxOrNull() ?: -1
    val tail = if (overlap >= 0) remaining.drop(overlap + 1) else remaining
    val prefix = old.ids.take(start)
    val freshIds = fresh.toSet()
    val ids = (prefix.filterNot { it in freshIds } + fresh + tail).distinct()
    val removed = remaining.take(if (overlap >= 0) overlap + 1 else 0).toSet()
    val gaps = old.gaps.filterNot {
        it.beforeId in removed || (it.beforeId == remaining.firstOrNull() && maxId != null)
    }.toMutableList()
    if (overlap < 0 && tail.isNotEmpty()) {
        val boundary = page.nextMaxId ?: fresh.lastOrNull() ?: maxId
        if (boundary != null) {
            gaps.removeAll { it.beforeId == tail.first() }
            gaps += HomeTimelineGap(tail.first(), boundary,
                unavailable = page.endReached || (maxId != null && boundary == maxId))
        }
    }
    val rows = (statuses.associateBy { it.timelineId } + page.statuses.associateBy { it.timelineId })
    val keepsTail = tail.isNotEmpty()
    return TimelinePage(ids.mapNotNull(rows::get),
        if (keepsTail) nextMaxId else page.nextMaxId,
        if (keepsTail) endReached else page.endReached,
        HomeTimelineIndex(ids, gaps.filter { it.beforeId in ids }.distinctBy { it.beforeId }, streamBoundary = false))
}

/** Gap requests may have advanced through an entirely hidden page; its cursor need not be visible. */
fun TimelinePage.fillHomeGap(gap: HomeTimelineGap, page: TimelinePage): TimelinePage {
    val index = homeIndex()
    val boundary = index.ids.indexOf(gap.beforeId)
    if (boundary < 0 || gap !in index.gaps || page.homeIndex().partial) return this
    val prefix = index.ids.take(boundary)
    if (page.homeIndex().ids.any { it in prefix }) {
        // A server ignoring max_id must not reorder the window or cause an endless retry loop.
        return copy(homeIndex = index.copy(gaps = index.gaps.map {
            if (it == gap) it.copy(unavailable = true) else it
        }))
    }
    // Place a hidden cursor at the boundary when it was not included in the preceding page.
    val withCursor = if (gap.maxId in prefix) this else copy(homeIndex = index.copy(ids =
        prefix + gap.maxId + index.ids.drop(boundary)))
    return withCursor.mergeHomePage(page, gap.maxId)
}

fun TimelinePage.applyHomeWindowChange(change: BrowsingSession.Change): TimelinePage {
    val index = homeIndex()
    if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.Connected)
        return copy(homeIndex = index.copy(streamBoundary = true))
    val rows = statuses.applyHomeChange(change, includeNew = true)
    val added = rows.map { it.timelineId }.filterNot { it in index.ids }
    val gaps = if (added.isNotEmpty() && index.streamBoundary && index.ids.isNotEmpty())
        listOf(HomeTimelineGap(index.ids.first(), added.last())) + index.gaps else index.gaps
    return copy(statuses = rows, homeIndex = index.copy(ids = added + index.ids, gaps = gaps,
        streamBoundary = if (added.isNotEmpty()) false else index.streamBoundary))
}

/** Keep latest rows and the reader's vicinity under the same bounded cache budget. */
fun TimelinePage.boundHomeWindow(anchorId: String?, limit: Int = 500): TimelinePage {
    val index = homeIndex()
    if (index.ids.size <= limit) return copy(homeIndex = index)
    val anchor = index.ids.indexOf(anchorId)
    val headSize = if (anchor >= limit) minOf(40, limit / 2) else limit
    val tailStart = if (headSize < limit) (anchor - 20).coerceAtLeast(headSize) else index.ids.size
    val ids = index.ids.take(headSize) + index.ids.drop(tailStart).take(limit - headSize)
    val gaps = index.gaps.filter { it.beforeId in ids }.toMutableList()
    if (headSize < limit && tailStart > headSize) {
        gaps.removeAll { it.beforeId == index.ids[tailStart] }
        gaps += HomeTimelineGap(index.ids[tailStart], index.ids[headSize - 1])
    }
    val keptEnd = ids.lastOrNull() == index.ids.lastOrNull()
    return copy(statuses = statuses.filter { it.timelineId in ids },
        nextMaxId = if (keptEnd) nextMaxId else ids.lastOrNull(), endReached = keptEnd && endReached,
        homeIndex = index.copy(ids = ids, gaps = gaps))
}
