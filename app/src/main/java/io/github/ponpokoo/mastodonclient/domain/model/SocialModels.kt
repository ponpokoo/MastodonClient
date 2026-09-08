package io.github.ponpokoo.mastodonclient.domain.model

enum class TimelineFeed {
    Home,
    Local,
    Federated,
}

data class UserProfile(
    val author: StatusAuthor,
    val headerUrl: String,
    val noteHtml: String,
    val followersCount: Long,
    val followingCount: Long,
    val statusesCount: Long,
    val statuses: List<TimelineStatus>,
)

data class TimelineNotification(
    val id: String,
    val type: String,
    val createdAt: String,
    val account: StatusAuthor,
    val status: TimelineStatus?,
)

data class NotificationPage(
    val notifications: List<TimelineNotification>,
    val nextMaxId: String?,
    val endReached: Boolean,
)

data class SearchTag(
    val name: String,
    val url: String,
)

data class SearchResults(
    val accounts: List<StatusAuthor>,
    val statuses: List<TimelineStatus>,
    val hashtags: List<SearchTag>,
)

sealed interface TimelineStreamEvent {
    data class StatusAdded(val status: TimelineStatus) : TimelineStreamEvent
    data class StatusDeleted(val statusId: String) : TimelineStreamEvent
    data class NotificationReceived(val notification: TimelineNotification) : TimelineStreamEvent
}
