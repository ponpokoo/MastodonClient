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
import io.github.ponpokoo.mastodonclient.domain.model.ComposerConfiguration
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.MediaUpload
import io.github.ponpokoo.mastodonclient.domain.model.UploadedMedia
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.model.ProfileField
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

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
        val statusesRequest = async { api.getAccountStatuses(accountId, excludeReplies = true) }
        val pinnedRequest = async {
            try {
                api.getAccountStatuses(accountId, pinned = true).map(StatusDto::toDomain)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
        val account = accountRequest.await()
        val statuses = statusesRequest.await().map(StatusDto::toDomain)
        val pinned = pinnedRequest.await()
        statuses.forEach { statusCache[it.statusId] = it }
        UserProfile(
            author = account.toDomain(),
            headerUrl = account.header,
            noteHtml = account.note,
            followersCount = account.followersCount,
            followingCount = account.followingCount,
            statusesCount = account.statusesCount,
            statuses = statuses,
            url = account.url,
            locked = account.locked,
            createdAt = account.createdAt,
            fields = account.fields.map { ProfileField(it.name, it.value, it.verifiedAt) },
            customEmojis = account.emojis.associate { it.shortcode to it.url },
            pinnedStatuses = pinned,
            nextMaxId = statuses.lastOrNull()?.statusId,
            endReached = statuses.size < 20,
            isOwnProfile = account.id == session.accountId,
        )
    } }

    override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> = runCatching {
        val response = apiClientFactory.create(session.instanceUrl, session.accessToken).getAccountStatuses(
            id = accountId,
            excludeReplies = tab == ProfileStatusTab.Posts,
            onlyMedia = tab == ProfileStatusTab.Media,
            maxId = maxId,
        )
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[it.statusId] = it }
        TimelinePage(statuses, response.lastOrNull()?.id, response.size < 20)
    }

    override suspend fun getAccountList(session: AccountSession, accountId: String, followers: Boolean, maxId: String?) = runCatching {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        (if (followers) api.getFollowers(accountId, maxId) else api.getFollowing(accountId, maxId)).map(AccountDto::toDomain)
    }

    override suspend fun getRelationship(session: AccountSession, accountId: String) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getRelationships(listOf(accountId)).first().toDomain()
    }

    override suspend fun setFollowing(session: AccountSession, accountId: String, following: Boolean) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).let { if (following) it.follow(accountId) else it.unfollow(accountId) }.toDomain()
    }

    override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).let { if (muted) it.mute(accountId) else it.unmute(accountId) }.toDomain()
    }

    override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).let { if (blocked) it.block(accountId) else it.unblock(accountId) }.toDomain()
    }

    override suspend fun reportAccount(session: AccountSession, accountId: String, comment: String, forward: Boolean) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).report(accountId, comment, forward)
        Unit
    }

    override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest): Result<UserProfile> = runCatching {
        val parts = linkedMapOf<String, okhttp3.RequestBody>()
        fun add(key: String, value: String) { parts[key] = value.toRequestBody("text/plain".toMediaType()) }
        add("display_name", request.displayName)
        add("note", request.note)
        add("locked", request.locked.toString())
        add("discoverable", request.discoverable.toString())
        request.fields.take(4).forEachIndexed { index, field ->
            add("fields_attributes[$index][name]", field.first)
            add("fields_attributes[$index][value]", field.second)
        }
        fun imagePart(name: String, path: String?): MultipartBody.Part? = path?.let { filePath ->
            val file = File(filePath)
            MultipartBody.Part.createFormData(name, file.name, file.asRequestBody("image/*".toMediaType()))
        }
        val account = apiClientFactory.create(session.instanceUrl, session.accessToken).updateCredentials(
            parts, imagePart("avatar", request.avatarFilePath), imagePart("header", request.headerFilePath),
        )
        UserProfile(
            author = account.toDomain(), headerUrl = account.header, noteHtml = account.note,
            followersCount = account.followersCount, followingCount = account.followingCount,
            statusesCount = account.statusesCount, statuses = emptyList(), url = account.url,
            locked = account.locked, createdAt = account.createdAt,
            fields = account.fields.map { ProfileField(it.name, it.value, it.verifiedAt) },
            customEmojis = account.emojis.associate { it.shortcode to it.url }, isOwnProfile = true,
        )
    }

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

    override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
        updateStatus(session) { if (bookmarked) bookmark(statusId) else unbookmark(statusId) }

    override suspend fun createStatus(
        session: AccountSession,
        text: String,
        replyToId: String?,
        idempotencyKey: String,
    ) = updateStatus(session) { createStatus(idempotencyKey, text, replyToId) }

    override suspend fun createStatus(
        session: AccountSession,
        request: CreateStatusRequest,
        idempotencyKey: String,
    ) = updateStatus(session) {
        createStatus(
            idempotencyKey = idempotencyKey,
            status = request.text,
            inReplyToId = request.replyToId,
            mediaIds = request.mediaIds.ifEmpty { null },
            spoilerText = request.spoilerText.ifBlank { null },
            sensitive = request.sensitive,
            visibility = request.visibility,
            language = request.language?.ifBlank { null },
            pollOptions = request.pollOptions.ifEmpty { null },
            pollExpiresInSeconds = request.pollExpiresInSeconds,
            pollMultiple = request.pollMultiple.takeIf { request.pollOptions.isNotEmpty() },
        )
    }

    override suspend fun getComposerConfiguration(session: AccountSession): Result<ComposerConfiguration> = runCatching {
        val configuration = apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getInstance().configuration
        ComposerConfiguration(
            maxCharacters = configuration?.statuses?.maxCharacters ?: 500,
            maxMediaAttachments = configuration?.statuses?.maxMediaAttachments ?: 4,
            mediaDescriptionLimit = configuration?.mediaAttachments?.descriptionLimit ?: 1_500,
            supportedMimeTypes = configuration?.mediaAttachments?.supportedMimeTypes.orEmpty().toSet(),
        )
    }

    override suspend fun getCustomEmojis(session: AccountSession): Result<List<CustomEmoji>> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getCustomEmojis().map {
            CustomEmoji(it.shortcode, it.url, it.staticUrl, it.category)
        }
    }

    override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> = runCatching {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val body = File(upload.filePath).asRequestBody(upload.mimeType.toMediaType())
        val file = MultipartBody.Part.createFormData("file", upload.fileName, body)
        val description = upload.description?.takeIf(String::isNotBlank)
            ?.toRequestBody("text/plain".toMediaType())
        var media = api.uploadMedia(file, description)
        for (attempt in 0 until 12) {
            if (media.url != null) break
            delay(500)
            media = api.getMedia(media.id)
        }
        UploadedMedia(media.id, media.type, media.previewUrl ?: media.url, media.description)
    }

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
        bookmarked = displayed.bookmarked,
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

private fun io.github.ponpokoo.mastodonclient.data.remote.dto.RelationshipDto.toDomain() = AccountRelationship(
    following = following, followedBy = followedBy, blocking = blocking, blockedBy = blockedBy,
    muting = muting, requested = requested,
)

private fun NotificationDto.toDomain() = TimelineNotification(
    id = id,
    type = type,
    createdAt = createdAt,
    account = account.toDomain(),
    status = status?.toDomain(),
)
