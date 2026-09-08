package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.NotificationDto
import io.github.ponpokoo.mastodonclient.data.remote.MastodonStreamingDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.SearchTag
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.NotificationPage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json

class DefaultTimelineRepository(
    private val apiClientFactory: ApiClientFactory,
    private val streamingDataSource: MastodonStreamingDataSource = MastodonStreamingDataSource(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : TimelineRepository {
    private val statusCache = ConcurrentHashMap<String, TimelineStatus>()

    override fun getCachedStatus(statusId: String): TimelineStatus? = statusCache[statusId]

    override suspend fun getAnnouncements(session: AccountSession): Result<List<ServerAnnouncement>> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getAnnouncements()
            .map {
                ServerAnnouncement(
                    id = it.id,
                    contentHtml = it.content,
                    publishedAt = it.publishedAt,
                    updatedAt = it.updatedAt,
                    read = it.read,
                )
            }
    }

    override suspend fun getProfile(session: AccountSession, accountId: String): Result<UserProfile> = runCatching { coroutineScope {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val accountRequest = async { api.getAccount(accountId) }
        val statusesRequest = async { api.getAccountStatuses(accountId) }
        val account = accountRequest.await()
        val statuses = statusesRequest.await().map(StatusDto::toDomain)
        statuses.forEach { statusCache[it.statusId] = it }
        UserProfile(
            author = account.toDomain(),
            headerUrl = account.header,
            noteHtml = account.note,
            followersCount = account.followersCount,
            followingCount = account.followingCount,
            statusesCount = account.statusesCount,
            statuses = statuses,
        )
    } }

    override suspend fun getNotifications(
        session: AccountSession,
        maxId: String?,
        limit: Int,
    ): Result<NotificationPage> = runCatching {
        require(limit in 1..80) { "通知の取得件数が範囲外です" }
        val notifications = apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getNotifications(maxId = maxId, limit = limit)
            .map(NotificationDto::toDomain)
        NotificationPage(
            notifications = notifications,
            nextMaxId = notifications.lastOrNull()?.id,
            endReached = notifications.size < limit,
        )
    }

    override suspend fun saveNotificationMarker(
        session: AccountSession,
        lastReadId: String,
    ): Result<Unit> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .saveNotificationMarker(lastReadId)
        Unit
    }

    override suspend fun search(session: AccountSession, query: String): Result<SearchResults> = runCatching {
        require(query.isNotBlank()) { "検索語を入力してください" }
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .search(query.trim())
            .let { result ->
                SearchResults(
                    accounts = result.accounts.map(AccountDto::toDomain),
                    statuses = result.statuses.map(StatusDto::toDomain),
                    hashtags = result.hashtags.map { SearchTag(it.name, it.url) },
                )
            }
    }

    override fun observeUserStream(session: AccountSession): Flow<TimelineStreamEvent> = flow {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val streamingUrl = runCatching {
            api.getInstance().let { it.configuration?.urls?.streaming ?: it.urls?.streaming }
        }.getOrNull()
            ?: session.instanceUrl
        emitAll(
            streamingDataSource.observeUser(streamingUrl, session.accessToken).mapNotNull { message ->
                when (message.event) {
                    "update", "status.update" -> runCatching {
                        TimelineStreamEvent.StatusAdded(json.decodeFromString<StatusDto>(message.payload).toDomain())
                    }.getOrNull()
                    "delete" -> TimelineStreamEvent.StatusDeleted(message.payload.trim('"'))
                    "notification" -> runCatching {
                        TimelineStreamEvent.NotificationReceived(
                            json.decodeFromString<NotificationDto>(message.payload).toDomain(),
                        )
                    }.getOrNull()
                    else -> null
                }
            },
        )
    }

    override suspend fun getHomeTimeline(
        session: AccountSession,
        maxId: String?,
        limit: Int,
    ): Result<TimelinePage> = runCatching {
        require(limit in 1..40) { "タイムラインの取得件数が範囲外です" }
        val response = apiClientFactory
            .create(session.instanceUrl, session.accessToken)
            .getHomeTimeline(maxId = maxId, limit = limit)
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[it.statusId] = it }
        TimelinePage(
            statuses = statuses,
            nextMaxId = response.lastOrNull()?.id,
            endReached = response.size < limit,
        )
    }

    override suspend fun getTimeline(
        session: AccountSession,
        feed: TimelineFeed,
        maxId: String?,
        limit: Int,
    ): Result<TimelinePage> = if (feed == TimelineFeed.Home) {
        getHomeTimeline(session, maxId, limit)
    } else runCatching {
        require(limit in 1..40) { "タイムラインの取得件数が範囲外です" }
        val response = apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getPublicTimeline(
                local = feed == TimelineFeed.Local,
                remote = false,
                maxId = maxId,
                limit = limit,
            )
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[it.statusId] = it }
        TimelinePage(
            statuses = statuses,
            nextMaxId = response.lastOrNull()?.id,
            endReached = response.size < limit,
        )
    }

    override suspend fun getHashtagTimeline(
        session: AccountSession,
        hashtag: String,
        maxId: String?,
        limit: Int,
    ): Result<TimelinePage> = runCatching {
        require(hashtag.isNotBlank()) { "ハッシュタグが空です" }
        require(limit in 1..40) { "タイムラインの取得件数が範囲外です" }
        val response = apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getHashtagTimeline(hashtag = hashtag, maxId = maxId, limit = limit)
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[it.statusId] = it }
        TimelinePage(
            statuses = statuses,
            nextMaxId = response.lastOrNull()?.id,
            endReached = response.size < limit,
        )
    }

    override suspend fun getStatusDetail(
        session: AccountSession,
        statusId: String,
    ): Result<StatusDetail> = runCatching { coroutineScope {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val statusRequest = async { api.getStatus(statusId) }
        val contextRequest = async { api.getStatusContext(statusId) }
        val status = statusRequest.await()
        val context = contextRequest.await()
        val mappedStatus = status.toDomain()
        statusCache[mappedStatus.statusId] = mappedStatus
        StatusDetail(
            status = mappedStatus,
            ancestors = context.ancestors.map(StatusDto::toDomain),
            descendants = context.descendants.map(StatusDto::toDomain),
        )
    } }

    override suspend fun getRebloggedBy(session: AccountSession, statusId: String) =
        loadAccounts(session) { getRebloggedBy(statusId) }

    override suspend fun getFavouritedBy(session: AccountSession, statusId: String) =
        loadAccounts(session) { getFavouritedBy(statusId) }

    override suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String) =
        loadAccounts(session) { getEmojiReactionedBy(statusId) }

    override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
        updateStatus(session) { if (favourite) favourite(statusId) else unfavourite(statusId) }

    override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean) =
        updateStatus(session) { if (reblogged) reblog(statusId) else unreblog(statusId) }

    override suspend fun createStatus(
        session: AccountSession,
        text: String,
        replyToId: String?,
        idempotencyKey: String,
    ) = updateStatus(session) { createStatus(idempotencyKey, text, replyToId) }

    override suspend fun setFedibirdReaction(
        session: AccountSession,
        statusId: String,
        emoji: String?,
    ) = updateStatus(session) {
        if (emoji == null) removeFedibirdReaction(statusId)
        else addFedibirdReaction(statusId, emoji)
    }

    private suspend fun updateStatus(
        session: AccountSession,
        request: suspend io.github.ponpokoo.mastodonclient.data.remote.MastodonApi.() -> StatusDto,
    ) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).request().toDomain()
            .also { statusCache[it.statusId] = it }
    }

    private suspend fun loadAccounts(
        session: AccountSession,
        request: suspend io.github.ponpokoo.mastodonclient.data.remote.MastodonApi.() -> List<AccountDto>,
    ) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .request()
            .map(AccountDto::toDomain)
    }
}

private fun StatusDto.toDomain(): TimelineStatus {
    val displayed = reblog ?: this
    return TimelineStatus(
        timelineId = id,
        statusId = displayed.id,
        createdAt = displayed.createdAt,
        author = displayed.account.toDomain(),
        boostedBy = account.takeIf { reblog != null }?.toDomain(),
        contentHtml = displayed.content,
        spoilerText = displayed.spoilerText,
        sensitive = displayed.sensitive,
        visibility = displayed.visibility,
        url = displayed.url,
        repliesCount = displayed.repliesCount,
        boostsCount = displayed.reblogsCount,
        favouritesCount = displayed.favouritesCount,
        favourited = displayed.favourited,
        reblogged = displayed.reblogged,
        applicationName = displayed.application?.name,
        reactions = displayed.emojiReactions.orEmpty().map {
            EmojiReaction(
                name = it.name,
                count = it.count,
                reactedByMe = it.me,
                imageUrl = it.staticUrl ?: it.url,
                accountIds = it.accountIds.toSet(),
            )
        },
        supportsEmojiReactions = displayed.emojiReactions != null,
        previewCard = displayed.card?.let { card ->
            PreviewCard(
                url = card.url,
                title = card.title,
                description = card.description,
                type = card.type,
                byline = card.authorName.ifBlank { card.providerName },
                imageUrl = card.image,
                aspectRatio = if (card.width > 0 && card.height > 0) {
                    card.width.toFloat() / card.height
                } else null,
            )
        },
        mediaAttachments = displayed.mediaAttachments.map {
            MediaAttachment(
                id = it.id,
                type = it.type,
                url = it.url,
                previewUrl = it.previewUrl,
                description = it.description,
            )
        },
    )
}

private fun AccountDto.toDomain() = StatusAuthor(
    id = id,
    displayName = displayName.ifBlank { username },
    accountName = acct,
    avatarUrl = avatar,
)

private fun NotificationDto.toDomain() = TimelineNotification(
    id = id,
    type = type,
    createdAt = createdAt,
    account = account.toDomain(),
    status = status?.toDomain(),
)
