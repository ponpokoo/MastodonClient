package io.github.ponpokoo.mastodonclient.feature.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import java.time.Duration
import java.time.Instant

/** Presentation only: the underlying notifications still drive paging, persistence and read state. */
internal data class NotificationDisplayRow(
    val key: String,
    val notifications: List<TimelineNotification>,
    val accounts: List<StatusAuthor>,
    val grouped: Boolean,
) {
    val latest: TimelineNotification get() = notifications.first()
    val notificationIds: Set<String> = notifications.mapTo(linkedSetOf(), TimelineNotification::id)
}

private data class NotificationGroupKey(val statusId: String, val kind: String, val emoji: String?, val imageUrl: String?) {
    val rowKey: String get() = "group:" + listOf(statusId, kind, emoji.orEmpty(), imageUrl.orEmpty())
        .joinToString("") { "${it.length}:$it" }
}

private fun TimelineNotification.groupKey(): NotificationGroupKey? {
    val statusId = status?.statusId ?: return null
    return when {
        type == "favourite" || type == "reblog" -> NotificationGroupKey(statusId, type, null, null)
        isEmojiReaction -> matchingReaction?.let {
            // Keep custom emoji with the same shortcode but different images separate.
            NotificationGroupKey(statusId, "reaction", it.name.trim(':'), it.imageUrl)
        }
        else -> null
    }
}

/** Derives actor order from the latest notifications so refresh and recreation show the same order. */
internal class NotificationRowGrouper {
    fun rows(notifications: List<TimelineNotification>, grouped: Boolean): List<NotificationDisplayRow> {
        val groups = linkedMapOf<String, MutableList<TimelineNotification>>()
        val groupedKeys = mutableSetOf<String>()
        notifications.distinctBy(TimelineNotification::id).forEach { notification ->
            val group = if (grouped) notification.groupKey() else null
            val key = group?.rowKey ?: "notification:${notification.id}"
            if (group != null) groupedKeys += key
            groups.getOrPut(key) { mutableListOf() } += notification
        }
        return groups.map { (key, events) ->
            // Input is already in notification time order; preserve ties without comparing opaque IDs.
            val actors = events.map(TimelineNotification::account).distinctBy(StatusAuthor::id)
            NotificationDisplayRow(key, events, actors, key in groupedKeys)
        }
    }
}

@Composable
internal fun rememberNotificationDisplayRows(
    notifications: List<TimelineNotification>,
    grouped: Boolean,
    sessionKey: String,
): List<NotificationDisplayRow> {
    val grouper = remember(sessionKey, grouped) { NotificationRowGrouper() }
    return remember(grouper, notifications) { grouper.rows(notifications, grouped) }
}

internal fun notificationRowsForCategory(
    notifications: List<TimelineNotification>,
    category: NotificationCategory,
    allRows: List<NotificationDisplayRow>,
): List<NotificationDisplayRow> = if (category == NotificationCategory.All) allRows
else NotificationRowGrouper().rows(notifications.filter(category::includes), grouped = false)

internal fun visibleNotificationIds(rows: List<NotificationDisplayRow>, visibleKeys: Set<Any>): Set<String> =
    rows.asSequence().filter { it.key in visibleKeys }.flatMap { it.notificationIds.asSequence() }.toSet()

internal fun notificationRelativeTime(value: String, now: Instant = Instant.now()): String = runCatching {
    val elapsed = Duration.between(Instant.parse(value), now).coerceAtLeast(Duration.ZERO)
    when {
        elapsed.seconds < 60 -> "今"
        elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()}分前"
        elapsed.toHours() < 24 -> "${elapsed.toHours()}時間前"
        else -> "${elapsed.toDays()}日前"
    }
}.getOrDefault("")
