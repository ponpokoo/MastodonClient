package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.AccountListPage
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.NotificationPage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.github.ponpokoo.mastodonclient.domain.model.ComposerConfiguration
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.MediaUpload
import io.github.ponpokoo.mastodonclient.domain.model.UploadedMedia
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import io.github.ponpokoo.mastodonclient.domain.model.EditableStatus

interface TimelineRepository {
    fun getCachedStatus(session: AccountSession, statusId: String): TimelineStatus? = null

    suspend fun getHomeTimeline(
        session: AccountSession,
        maxId: String? = null,
        limit: Int = 20,
    ): Result<TimelinePage>

    suspend fun getTimeline(
        session: AccountSession,
        feed: TimelineFeed,
        maxId: String? = null,
        limit: Int = 20,
    ): Result<TimelinePage> = if (feed == TimelineFeed.Home) {
        getHomeTimeline(session, maxId, limit)
    } else {
        Result.failure(UnsupportedOperationException("公開タイムラインは未対応です"))
    }

    suspend fun getHashtagTimeline(
        session: AccountSession,
        hashtag: String,
        maxId: String? = null,
        limit: Int = 20,
    ): Result<TimelinePage> = Result.failure(UnsupportedOperationException("ハッシュタグタイムラインは未対応です"))

    suspend fun getAnnouncements(session: AccountSession): Result<List<ServerAnnouncement>> =
        Result.failure(UnsupportedOperationException("サーバーからのお知らせは未対応です"))

    suspend fun getProfile(session: AccountSession, accountId: String = session.accountId): Result<UserProfile> =
        Result.failure(UnsupportedOperationException("プロフィールは未対応です"))

    suspend fun getProfileHeader(session: AccountSession, accountId: String = session.accountId): Result<UserProfile> =
        getProfile(session, accountId)

    suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String): Result<List<TimelineStatus>> =
        getProfile(session, accountId).map(UserProfile::pinnedStatuses)

    suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String? = null): Result<TimelinePage> =
        Result.failure(UnsupportedOperationException("プロフィール投稿は未対応です"))
    suspend fun getAccountList(session: AccountSession, accountId: String, followers: Boolean, maxId: String? = null): Result<List<StatusAuthor>> =
        Result.failure(UnsupportedOperationException("アカウント一覧は未対応です"))
    suspend fun getAccountListPage(session: AccountSession, accountId: String, followers: Boolean, maxId: String? = null): Result<AccountListPage> =
        getAccountList(session, accountId, followers, maxId).map { accounts ->
            AccountListPage(accounts, accounts.lastOrNull()?.id, accounts.isEmpty())
        }
    suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> =
        Result.failure(UnsupportedOperationException("フォロー関係は未対応です"))
    suspend fun getRelationships(session: AccountSession, accountIds: List<String>): Result<Map<String, AccountRelationship>> =
        runCatching { accountIds.associateWith { getRelationship(session, it).getOrThrow() } }
    suspend fun setFollowing(session: AccountSession, accountId: String, following: Boolean): Result<AccountRelationship> =
        Result.failure(UnsupportedOperationException("フォロー操作は未対応です"))
    suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> =
        Result.failure(UnsupportedOperationException("ミュート操作は未対応です"))
    suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> =
        Result.failure(UnsupportedOperationException("ブロック操作は未対応です"))
    suspend fun reportAccount(session: AccountSession, accountId: String, comment: String, forward: Boolean): Result<Unit> =
        Result.failure(UnsupportedOperationException("通報は未対応です"))
    suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest): Result<UserProfile> =
        Result.failure(UnsupportedOperationException("プロフィール編集は未対応です"))

    suspend fun getNotifications(
        session: AccountSession,
        maxId: String? = null,
        limit: Int = 80,
    ): Result<NotificationPage> =
        Result.failure(UnsupportedOperationException("通知は未対応です"))

    suspend fun saveNotificationMarker(session: AccountSession, lastReadId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("通知の既読保存は未対応です"))

    suspend fun search(session: AccountSession, query: String): Result<SearchResults> =
        Result.failure(UnsupportedOperationException("検索は未対応です"))

    fun observeUserStream(session: AccountSession): Flow<TimelineStreamEvent> = emptyFlow()

    suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> =
        Result.failure(UnsupportedOperationException("投稿詳細は未対応です"))

    suspend fun getRebloggedBy(session: AccountSession, statusId: String): Result<List<StatusAuthor>> =
        Result.failure(UnsupportedOperationException("ブースト一覧は未対応です"))

    suspend fun getFavouritedBy(session: AccountSession, statusId: String): Result<List<StatusAuthor>> =
        Result.failure(UnsupportedOperationException("お気に入り一覧は未対応です"))

    suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String, reactionName: String): Result<List<StatusAuthor>> =
        Result.failure(UnsupportedOperationException("リアクション一覧は未対応です"))

    suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("お気に入り操作は未対応です"))

    suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("ブースト操作は未対応です"))

    suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("ブックマーク操作は未対応です"))

    suspend fun votePoll(
        session: AccountSession,
        statusId: String,
        pollId: String,
        choices: Set<Int>,
    ): Result<TimelineStatus> = Result.failure(UnsupportedOperationException("アンケート投票は未対応です"))

    suspend fun createStatus(
        session: AccountSession,
        text: String,
        replyToId: String? = null,
        idempotencyKey: String,
    ): Result<TimelineStatus> = Result.failure(UnsupportedOperationException("投稿は未対応です"))

    suspend fun createStatus(
        session: AccountSession,
        request: CreateStatusRequest,
        idempotencyKey: String,
    ): Result<TimelineStatus> = createStatus(session, request.text, request.replyToId, idempotencyKey)

    suspend fun getComposerConfiguration(session: AccountSession): Result<ComposerConfiguration> =
        Result.success(ComposerConfiguration())

    suspend fun getCustomEmojis(session: AccountSession): Result<List<CustomEmoji>> =
        Result.success(emptyList())

    suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> =
        Result.failure(UnsupportedOperationException("メディアアップロードは未対応です"))

    suspend fun setFedibirdReaction(
        session: AccountSession,
        statusId: String,
        emoji: String?,
    ): Result<TimelineStatus> = Result.failure(UnsupportedOperationException("リアクション操作は未対応です"))

    suspend fun setPinned(session: AccountSession, statusId: String, pinned: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("固定操作は未対応です"))
    suspend fun deleteStatus(session: AccountSession, statusId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("削除操作は未対応です"))
    suspend fun getEditableStatus(session: AccountSession, statusId: String): Result<EditableStatus> =
        Result.failure(UnsupportedOperationException("編集元の取得は未対応です"))
    suspend fun updateStatus(session: AccountSession, status: EditableStatus): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("投稿編集は未対応です"))
    suspend fun getLists(session: AccountSession): Result<List<MastodonList>> =
        Result.failure(UnsupportedOperationException("リストは未対応です"))
    suspend fun addAccountToList(session: AccountSession, listId: String, accountId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("リスト追加は未対応です"))
    suspend fun getSavedTimeline(
        session: AccountSession,
        kind: SavedTimelineKind,
        listId: String? = null,
        maxId: String? = null,
    ): Result<TimelinePage> = Result.failure(UnsupportedOperationException("保存済みタイムラインは未対応です"))
    suspend fun reportStatus(session: AccountSession, accountId: String, statusId: String, comment: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("投稿の通報は未対応です"))
}
