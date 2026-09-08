package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
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

interface TimelineRepository {
    fun getCachedStatus(statusId: String): TimelineStatus? = null

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

    suspend fun getNotifications(
        session: AccountSession,
        maxId: String? = null,
        limit: Int = 40,
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

    suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String): Result<List<StatusAuthor>> =
        Result.failure(UnsupportedOperationException("リアクション一覧は未対応です"))

    suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("お気に入り操作は未対応です"))

    suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean): Result<TimelineStatus> =
        Result.failure(UnsupportedOperationException("ブースト操作は未対応です"))

    suspend fun createStatus(
        session: AccountSession,
        text: String,
        replyToId: String? = null,
        idempotencyKey: String,
    ): Result<TimelineStatus> = Result.failure(UnsupportedOperationException("投稿は未対応です"))

    suspend fun setFedibirdReaction(
        session: AccountSession,
        statusId: String,
        emoji: String?,
    ): Result<TimelineStatus> = Result.failure(UnsupportedOperationException("リアクション操作は未対応です"))
}
