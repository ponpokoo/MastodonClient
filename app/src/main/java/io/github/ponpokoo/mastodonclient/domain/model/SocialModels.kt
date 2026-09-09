package io.github.ponpokoo.mastodonclient.domain.model

enum class TimelineFeed {
    Home,
    Local,
    Federated,
}

data class MastodonList(val id: String, val title: String)

enum class SavedTimelineKind { List, Bookmarks, Favourites }

data class UserProfile(
    val author: StatusAuthor,
    val headerUrl: String,
    val noteHtml: String,
    val followersCount: Long,
    val followingCount: Long,
    val statusesCount: Long,
    val statuses: List<TimelineStatus>,
    val url: String = "",
    val locked: Boolean = false,
    val createdAt: String? = null,
    val fields: List<ProfileField> = emptyList(),
    val customEmojis: Map<String, String> = emptyMap(),
    val pinnedStatuses: List<TimelineStatus> = emptyList(),
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val isOwnProfile: Boolean = false,
)

data class ProfileField(val name: String, val valueHtml: String, val verifiedAt: String?)

data class AccountRelationship(
    val following: Boolean = false,
    val followedBy: Boolean = false,
    val blocking: Boolean = false,
    val blockedBy: Boolean = false,
    val muting: Boolean = false,
    val requested: Boolean = false,
)

enum class ProfileStatusTab { Posts, Replies, Media }

data class ProfileEditRequest(
    val displayName: String,
    val note: String,
    val locked: Boolean,
    val discoverable: Boolean,
    val fields: List<Pair<String, String>> = emptyList(),
    val avatarFilePath: String? = null,
    val headerFilePath: String? = null,
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
