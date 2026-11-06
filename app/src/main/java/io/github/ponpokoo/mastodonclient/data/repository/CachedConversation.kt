package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus

/** Re-select within the fetched range without turning sibling branches into replies. */
internal fun List<TimelineStatus>.conversationAround(statusId: String): StatusDetail? {
    val byId = associateBy { it.statusId }
    val selected = byId[statusId] ?: return null
    val ancestors = mutableListOf<TimelineStatus>()
    val visited = mutableSetOf(statusId)
    var parentId = selected.inReplyToId
    while (parentId != null) {
        if (!visited.add(parentId)) return null
        val parent = byId[parentId] ?: break
        ancestors += parent
        parentId = parent.inReplyToId
    }
    val replyIds = mutableSetOf(statusId)
    do {
        val previousSize = replyIds.size
        for (post in this) {
            if (post.inReplyToId in replyIds && post.statusId !in visited) replyIds += post.statusId
        }
    } while (replyIds.size != previousSize)
    return StatusDetail(selected, ancestors.asReversed(), filter { it.statusId != statusId && it.statusId in replyIds })
}
