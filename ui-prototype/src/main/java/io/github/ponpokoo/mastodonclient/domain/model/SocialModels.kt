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
    val mutingNotifications: Boolean? = null,
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

@kotlinx.serialization.Serializable
data class TimelineNotification(
    val id: String,
    val type: String,
    val createdAt: String,
    val account: StatusAuthor,
    val status: TimelineStatus?,
) {
    /** The status aggregate can change; show an emoji only when this actor has one unique match. */
    val matchingReaction: EmojiReaction?
        get() = status?.reactions?.singleOrNull { account.id in it.accountIds }
}

data class NotificationPage(
    val notifications: List<TimelineNotification>,
    val nextMaxId: String?,
    val endReached: Boolean,
    val serverFiltered: Boolean = true,
)

/** A bounded display snapshot, not a complete or necessarily current server history. */
data class CachedNotifications(
    val notifications: List<TimelineNotification> = emptyList(),
    val lastReadId: String? = null,
    val readState: NotificationReadState = NotificationReadState(),
)

data class SearchTag(
    val name: String,
    val url: String,
    val postingAccounts: Long? = null,
    val following: Boolean? = null,
)

enum class SearchTarget { Posts, Accounts, Hashtags }

data class SearchResults(
    val accounts: List<StatusAuthor>,
    val statuses: List<TimelineStatus>,
    val hashtags: List<SearchTag>,
) {
    fun count(target: SearchTarget): Int = when (target) {
        SearchTarget.Posts -> statuses.size
        SearchTarget.Accounts -> accounts.size
        SearchTarget.Hashtags -> hashtags.size
    }
}

data class SearchPage(val results: SearchResults, val nextOffset: Int?, val endReached: Boolean)

enum class SearchFailure { Timeout, Connection, Authentication, RateLimited, Server, Rejected, Unknown }

class SearchException(val failure: SearchFailure, cause: Throwable) : Exception(cause)

sealed interface TimelineStreamEvent {
    data class StatusAdded(val status: TimelineStatus, val isEdit: Boolean = false) : TimelineStreamEvent
    data class StatusDeleted(val statusId: String) : TimelineStreamEvent
    data class NotificationReceived(val notification: TimelineNotification) : TimelineStreamEvent
}
