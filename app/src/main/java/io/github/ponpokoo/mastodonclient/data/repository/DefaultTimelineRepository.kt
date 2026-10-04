package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable as runCatching
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.local.NotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.CachedNotifications
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.NotificationDto
import io.github.ponpokoo.mastodonclient.data.remote.MastodonStreamingDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.AccountListPage
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
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.SearchPage
import io.github.ponpokoo.mastodonclient.domain.model.SearchException
import io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed
import io.github.ponpokoo.mastodonclient.domain.model.ExplorePage
import io.github.ponpokoo.mastodonclient.domain.model.ExploreNews
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
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import io.github.ponpokoo.mastodonclient.domain.model.EditableStatus
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import okio.buffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import io.github.ponpokoo.mastodonclient.domain.model.MediaValidator

class DefaultTimelineRepository(
    private val apiClientFactory: ApiClientFactory,
    private val streamingDataSource: MastodonStreamingDataSource = MastodonStreamingDataSource(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val onReactionSucceeded: suspend (AccountSession, String) -> Unit = { _, _ -> },
    private val notificationLocalDataSource: NotificationLocalDataSource? = null,
    private val homeTimelineLocalDataSource: io.github.ponpokoo.mastodonclient.data.local.HomeTimelineLocalDataSource? = null,
    private val networkAvailable: () -> Boolean = { true },
    private val configurationClock: () -> Long = { System.nanoTime() / 1_000_000 },
) : TimelineRepository {
    private data class CachedConfiguration(val value: ComposerConfiguration, val fetchedAt: Long)
    private val configurationMutex = Mutex()
    private val configurations = object : LinkedHashMap<String, CachedConfiguration>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedConfiguration>?) = size > 8
    }
    override fun isNetworkAvailable() = networkAvailable()
    override suspend fun getCachedHomeTimeline(session: AccountSession, maxId: String?, limit: Int, anchorId: String?): Result<TimelinePage?> = runCatching {
        homeTimelineLocalDataSource?.readPage(session, maxId, limit, anchorId)?.also { page -> page.statuses.forEach { cacheStatus(session, it) } }
    }
    override suspend fun cacheHomeTimeline(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession.Change>): Result<Unit> = runCatching {
        homeTimelineLocalDataSource?.writePage(session, maxId, page, changes)
        Unit
    }
    override suspend fun updateHomeTimelineCache(session: AccountSession, change: io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession.Change): Result<Unit> = runCatching {
        homeTimelineLocalDataSource?.applyChange(session, change)
        Unit
    }
    private data class StatusCacheKey(val instanceUrl: String, val sessionId: String, val statusId: String)
    private fun cacheKey(session: AccountSession, statusId: String) =
        StatusCacheKey(session.instanceUrl.trimEnd('/'), session.sessionId, statusId)

    private val statusCache = Collections.synchronizedMap(
        object : LinkedHashMap<StatusCacheKey, TimelineStatus>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StatusCacheKey, TimelineStatus>?) = size > 500
        },
    )

    override fun getCachedStatus(session: AccountSession, statusId: String): TimelineStatus? = statusCache[cacheKey(session, statusId)]

    private fun cacheStatus(session: AccountSession, status: TimelineStatus): TimelineStatus = status.also {
        statusCache[cacheKey(session, it.statusId)] = it
    }

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
        pinned.forEach { cacheStatus(session, it) }
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
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

    override suspend fun getProfileHeader(session: AccountSession, accountId: String): Result<UserProfile> = runCatching {
        val account = apiClientFactory.create(session.instanceUrl, session.accessToken).getAccount(accountId)
        UserProfile(
            author = account.toDomain(),
            headerUrl = account.header,
            noteHtml = account.note,
            followersCount = account.followersCount,
            followingCount = account.followingCount,
            statusesCount = account.statusesCount,
            statuses = emptyList(),
            url = account.url,
            locked = account.locked,
            createdAt = account.createdAt,
            fields = account.fields.map { ProfileField(it.name, it.value, it.verifiedAt) },
            customEmojis = account.emojis.associate { it.shortcode to it.url },
            pinnedStatuses = emptyList(),
            nextMaxId = null,
            endReached = false,
            isOwnProfile = account.id == session.accountId,
        )
    }

    override suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String): Result<List<TimelineStatus>> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getAccountStatuses(accountId, pinned = true).map(StatusDto::toDomain)
            .onEach { cacheStatus(session, it) }
    }

    override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> = runCatching {
        val response = apiClientFactory.create(session.instanceUrl, session.accessToken).getAccountStatuses(
            id = accountId,
            excludeReplies = tab == ProfileStatusTab.Posts,
            onlyMedia = tab == ProfileStatusTab.Media,
            maxId = maxId,
        )
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
        TimelinePage(statuses, response.lastOrNull()?.id, response.size < 20)
    }

    override suspend fun getAccountList(session: AccountSession, accountId: String, followers: Boolean, maxId: String?) =
        getAccountListPage(session, accountId, followers, maxId).map(AccountListPage::accounts)

    override suspend fun getAccountListPage(session: AccountSession, accountId: String, followers: Boolean, maxId: String?) = runCatching {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val response = if (followers) api.getFollowers(accountId, maxId) else api.getFollowing(accountId, maxId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val accounts = response.body().orEmpty().map(AccountDto::toDomain)
        val nextMaxId = nextAccountListCursor(response.headers()["Link"])
        AccountListPage(accounts, nextMaxId, nextMaxId == null || accounts.isEmpty())
    }

    override suspend fun getLists(session: AccountSession) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getLists()
            .map { MastodonList(it.id, it.title) }
    }

    override suspend fun addAccountToList(session: AccountSession, listId: String, accountId: String) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .addAccountsToList(listId, listOf(accountId))
        Unit
    }

    override suspend fun getSavedTimeline(
        session: AccountSession,
        kind: SavedTimelineKind,
        listId: String?,
        maxId: String?,
    ) = runCatching {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val response = when (kind) {
            SavedTimelineKind.List -> api.getListTimeline(requireNotNull(listId), maxId)
            SavedTimelineKind.Bookmarks -> api.getBookmarks(maxId)
            SavedTimelineKind.Favourites -> api.getFavourites(maxId)
        }
        val statuses = response.map(StatusDto::toDomain)
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
        TimelinePage(statuses, response.lastOrNull()?.id, response.size < 20)
    }

    override suspend fun getRelationship(session: AccountSession, accountId: String) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getRelationships(listOf(accountId)).first().toDomain()
    }

    override suspend fun getRelationships(session: AccountSession, accountIds: List<String>) = runCatching {
        if (accountIds.isEmpty()) emptyMap()
        else apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getRelationships(accountIds).associate { it.id to it.toDomain() }
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

    override suspend fun reportStatus(session: AccountSession, accountId: String, statusId: String, comment: String) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .report(accountId, comment, statusIds = listOf(statusId))
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
        notifications.mapNotNull(TimelineNotification::status).forEach { cacheStatus(session, it) }
        NotificationPage(
            notifications = notifications,
            nextMaxId = notifications.lastOrNull()?.id,
            // Instances can cap the page below the requested limit. Only an empty
            // response proves there are no older notifications to request.
            endReached = notifications.isEmpty(),
        )
    }

    override suspend fun getCachedNotifications(session: AccountSession): Result<CachedNotifications> = runCatching {
        notificationLocalDataSource?.read(session) ?: CachedNotifications()
    }

    override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> = runCatching {
        notificationLocalDataSource?.write(session, notifications)
        Unit
    }

    override suspend fun saveNotificationMarker(
        session: AccountSession,
        lastReadId: String,
    ): Result<Unit> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .saveNotificationMarker(lastReadId)
        // Cache failures must not turn a successful remote write into a failed read acknowledgement.
        runCatching { notificationLocalDataSource?.writeMarker(session, lastReadId) }
        Unit
    }

    override suspend fun getNotificationMarker(session: AccountSession): Result<String?> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getNotificationMarker().notifications?.lastReadId
            .also { marker -> runCatching { notificationLocalDataSource?.writeMarker(session, marker) } }
    }

    override suspend fun search(
        session: AccountSession,
        query: String,
        target: SearchTarget,
        offset: Int,
        limit: Int,
    ): Result<SearchPage> = runCatching {
        require(query.isNotBlank()) { "検索語を入力してください" }
        require(offset >= 0 && limit in 1..40)
        apiClientFactory.createForSearch(session.instanceUrl, session.accessToken)
            .search(query.trim(), target.apiType(), limit, offset)
            .let { result ->
                val results = SearchResults(
                    accounts = if (target == SearchTarget.Accounts) result.accounts.map(AccountDto::toDomain) else emptyList(),
                    statuses = if (target == SearchTarget.Posts) result.statuses.map(StatusDto::toDomain).onEach { cacheStatus(session, it) } else emptyList(),
                    hashtags = if (target == SearchTarget.Hashtags) result.hashtags.map { it.toSearchTag() } else emptyList(),
                )
                val count = results.count(target)
                // Short pages are possible on older servers; stop only when a page is empty.
                SearchPage(results, if (count == 0) null else offset + count, count == 0)
            }
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(SearchException(it.toSearchFailure(), it)) },
    )

    override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> = exploreRequest {
        require(limit in 1..20)
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        if (feed == ExploreFeed.Followed) {
            val response = api.getFollowedTags(limit, cursor)
            if (!response.isSuccessful) throw HttpException(response)
            ExplorePage(tags = response.body().orEmpty().map { it.toSearchTag().copy(following = true) },
                nextCursor = nextAccountListCursor(response.headers()["Link"]))
        } else {
            val offset = cursor?.toIntOrNull() ?: 0
            require(offset >= 0)
            val page = when (feed) {
                ExploreFeed.Posts -> ExplorePage(statuses = api.getTrendingStatuses(limit, offset).map { cacheStatus(session, it.toDomain()) })
                ExploreFeed.Hashtags -> ExplorePage(tags = api.getTrendingTags(limit, offset).map { it.toSearchTag() })
                ExploreFeed.News -> ExplorePage(news = api.getTrendingLinks(limit, offset).map {
                    ExploreNews(it.url, it.title, it.description, it.providerName, it.image?.takeIf(String::isNotBlank))
                })
                ExploreFeed.Followed -> error("Handled above")
            }
            page.copy(nextCursor = if (page.count() == 0) null else (offset + page.count()).toString())
        }
    }

    override suspend fun getTag(session: AccountSession, name: String): Result<SearchTag> = exploreRequest {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getTag(name).toSearchTag()
    }

    override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean): Result<SearchTag> = exploreRequest {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        (if (following) api.followTag(name) else api.unfollowTag(name)).toSearchTag().copy(following = following)
    }

    private suspend fun <T> exploreRequest(block: suspend () -> T): Result<T> = runCatching { block() }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { error -> Result.failure(
            if (error is HttpException && error.code() in setOf(404, 405, 501)) UnsupportedOperationException("探索APIは未対応です", error)
            else SearchException(error.toSearchFailure(), error),
        ) },
    )

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
                        TimelineStreamEvent.StatusAdded(cacheStatus(session, json.decodeFromString<StatusDto>(message.payload).toDomain()), isEdit = message.event == "status.update")
                    }.getOrNull()
                    "delete" -> TimelineStreamEvent.StatusDeleted(message.payload.trim('"')).also {
                        statusCache.remove(cacheKey(session, it.statusId))
                    }
                    "notification" -> runCatching {
                        TimelineStreamEvent.NotificationReceived(
                            json.decodeFromString<NotificationDto>(message.payload).toDomain().also {
                                it.status?.let { status -> cacheStatus(session, status) }
                            },
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
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
        TimelinePage(
            statuses = statuses,
            nextMaxId = response.lastOrNull()?.id,
            // Instances may return fewer than the requested limit before the history ends.
            endReached = response.isEmpty(),
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
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
        TimelinePage(
            statuses = statuses,
            nextMaxId = response.lastOrNull()?.id,
            endReached = response.size < limit,
        )
    }

    override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getStatus(timelineId).toDomain()
            .also { cacheStatus(session, it) }
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
        statuses.forEach { statusCache[cacheKey(session, it.statusId)] = it }
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
        statusCache[cacheKey(session, mappedStatus.statusId)] = mappedStatus
        StatusDetail(
            status = mappedStatus,
            ancestors = context.ancestors.map(StatusDto::toDomain).onEach { cacheStatus(session, it) },
            descendants = context.descendants.map(StatusDto::toDomain).onEach { cacheStatus(session, it) },
        )
    } }

    override suspend fun getRebloggedBy(session: AccountSession, statusId: String) =
        loadAccounts(session) { getRebloggedBy(statusId) }

    override suspend fun getFavouritedBy(session: AccountSession, statusId: String) =
        loadAccounts(session) { getFavouritedBy(statusId) }

    override suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String, reactionName: String) = runCatching {
        val response = apiClientFactory.create(session.instanceUrl, session.accessToken)
            .getEmojiReactionedBy(statusId)
        reactionAccountsFromResponse(response, reactionName, json).map(AccountDto::toDomain)
    }

    override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
        updateStatus(session) { if (favourite) favourite(statusId) else unfavourite(statusId) }

    override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean) =
        updateStatus(session) { if (reblogged) reblog(statusId) else unreblog(statusId) }

    override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
        updateStatus(session) { if (bookmarked) bookmark(statusId) else unbookmark(statusId) }

    override suspend fun votePoll(
        session: AccountSession,
        statusId: String,
        pollId: String,
        choices: Set<Int>,
    ) = runCatching {
        require(choices.isNotEmpty()) { "投票する選択肢を選んでください" }
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val poll = api.votePoll(pollId, choices.sorted())
        val cached = statusCache[cacheKey(session, statusId)] ?: api.getStatus(statusId).toDomain()
        cached.copy(poll = poll.toDomain()).also { updated ->
            statusCache[cacheKey(session, statusId)] = updated
        }
    }

    override suspend fun setPinned(session: AccountSession, statusId: String, pinned: Boolean) =
        updateStatus(session) { if (pinned) pin(statusId) else unpin(statusId) }

    override suspend fun deleteStatus(session: AccountSession, statusId: String) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).deleteStatus(statusId)
        statusCache.remove(cacheKey(session, statusId))
        Unit
    }

    override suspend fun getEditableStatus(session: AccountSession, statusId: String) = runCatching {
        val api = apiClientFactory.create(session.instanceUrl, session.accessToken)
        val source = api.getStatusSource(statusId)
        val status = api.getStatus(statusId)
        EditableStatus(
            id = source.id,
            text = source.text,
            spoilerText = source.spoilerText,
            sensitive = status.sensitive,
            language = status.language,
        )
    }

    override suspend fun updateStatus(session: AccountSession, status: EditableStatus) =
        updateStatus(session) {
            updateStatus(
                id = status.id,
                status = status.text,
                spoilerText = status.spoilerText.ifBlank { null },
                sensitive = status.sensitive,
                language = status.language?.ifBlank { null },
            )
        }

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
            quotedStatusId = request.quotedStatusId,
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
        configurationMutex.withLock {
            val key = session.instanceUrl.trimEnd('/')
            configurations[key]?.takeIf { configurationClock() - it.fetchedAt < 5 * 60_000 }
                ?.let { return@withLock it.value }
            val api = apiClientFactory.create(session.instanceUrl)
            val configuration = try { api.getInstance() } catch (error: HttpException) {
                if (error.code() != 404) throw error
                api.getLegacyInstance()
            }.configuration
            val result = ComposerConfiguration(
                maxCharacters = configuration?.statuses?.maxCharacters ?: 500,
                maxMediaAttachments = configuration?.statuses?.maxMediaAttachments
                    ?: configuration?.mediaAttachments?.maxAttachments ?: 4,
                mediaDescriptionLimit = configuration?.mediaAttachments?.descriptionLimit ?: 1_500,
                supportedMimeTypes = configuration?.mediaAttachments?.supportedMimeTypes
                    ?.mapNotNull(MediaValidator::normalizeMimeType)?.toSet(),
            )
            // Missing capabilities and failed requests must remain retryable.
            if (result.supportedMimeTypes != null) configurations[key] = CachedConfiguration(result, configurationClock())
            result
        }
    }

    override suspend fun getCustomEmojis(session: AccountSession): Result<List<CustomEmoji>> = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).getCustomEmojis().map {
            CustomEmoji(it.shortcode, it.url, it.staticUrl, it.category)
        }
    }

    override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> = mediaResult {
        val api = apiClientFactory.createForMedia(session.instanceUrl, session.accessToken)
        val source = File(upload.filePath).asRequestBody(upload.mimeType.toMediaType())
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = source.contentType()
            override fun contentLength() = source.contentLength()
            override fun writeTo(sink: okio.BufferedSink) {
                var sent = 0L
                var lastPercent = -1
                val counter = object : okio.ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        sent += byteCount
                        val progress = (sent.toFloat() / contentLength().coerceAtLeast(1)).coerceIn(0f, 1f)
                        val percent = (progress * 100).toInt()
                        if (percent != lastPercent) { lastPercent = percent; upload.onProgress(progress) }
                    }
                }
                val buffered = counter.buffer()
                source.writeTo(buffered)
                buffered.flush()
            }
        }
        val file = MultipartBody.Part.createFormData("file", upload.fileName, body)
        val description = upload.description?.takeIf(String::isNotBlank)
            ?.toRequestBody("text/plain".toMediaType())
        val media = api.uploadMedia(file, description)
        UploadedMedia(media.id, media.type, media.previewUrl ?: media.url, media.description, !media.url.isNullOrBlank())
    }

    override suspend fun checkMedia(session: AccountSession, id: String): Result<UploadedMedia> = mediaResult {
        val response = apiClientFactory.createForMedia(session.instanceUrl, session.accessToken).getMedia(id)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        if (response.code() == 206) {
            response.body()?.close()
            UploadedMedia(id, "", null, null, false)
        }
        else {
            val body = response.body()?.use { it.string() } ?: error("Missing media response")
            val media = json.decodeFromString<io.github.ponpokoo.mastodonclient.data.remote.dto.MediaAttachmentDto>(body)
            UploadedMedia(media.id, media.type, media.previewUrl, media.description, !media.url.isNullOrBlank())
        }
    }

    override suspend fun updateMediaDescription(session: AccountSession, id: String, description: String): Result<Unit> = mediaResult {
        apiClientFactory.createForMedia(session.instanceUrl, session.accessToken).updateMediaDescription(id, description)
        Unit
    }

    private suspend fun <T> mediaResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    override suspend fun setFedibirdReaction(
        session: AccountSession,
        statusId: String,
        emoji: String?,
    ): Result<TimelineStatus> {
        val result = updateStatus(session) {
            if (emoji == null) removeFedibirdReaction(statusId)
            else addFedibirdReaction(statusId, emoji)
        }
        if (emoji != null && result.isSuccess) {
            // History storage must not turn a successful server action into a failed one.
            runCatching { onReactionSucceeded(session, emoji) }
        }
        return result
    }

    private suspend fun updateStatus(
        session: AccountSession,
        request: suspend io.github.ponpokoo.mastodonclient.data.remote.MastodonApi.() -> StatusDto,
    ) = runCatching {
        apiClientFactory.create(session.instanceUrl, session.accessToken).request().toDomain()
            .also { statusCache[cacheKey(session, it.statusId)] = it }
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

/** Fedibird returns accounts directly; some compatible servers group them under a reaction. */
internal fun reactionAccountsFromResponse(response: JsonElement, reactionName: String, json: Json): List<AccountDto> {
    fun collect(value: JsonElement): List<AccountDto> = when (value) {
        is JsonArray -> value.flatMap(::collect)
        is JsonObject -> {
            if (listOf("id", "username", "acct").all(value::containsKey)) {
                listOf(json.decodeFromJsonElement<AccountDto>(value))
            } else {
                val name = ((value["name"] ?: value["emoji"] ?: value["reaction"]) as? JsonPrimitive)
                    ?.contentOrNull
                if (name != null && name.trim(':') != reactionName.trim(':')) {
                    emptyList()
                } else {
                    val nested = listOf("accounts", "account", "users", "reactors", "items", "data")
                        .mapNotNull(value::get)
                    val children = nested.ifEmpty {
                        listOfNotNull(value[reactionName], value[reactionName.trim(':')]).distinct()
                    }
                    children.flatMap(::collect)
                }
            }
        }
        else -> emptyList()
    }
    return collect(response).distinctBy(AccountDto::id)
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
        customEmojis = displayed.emojis.associate { it.shortcode to it.url },
        mentions = displayed.mentions.map { io.github.ponpokoo.mastodonclient.domain.model.StatusMention(it.id, it.acct, it.url) },
        quoteApproval = displayed.quoteApproval?.currentUser,
        inReplyToId = displayed.inReplyToId,
        inReplyToAccountId = displayed.inReplyToAccountId,
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
        pinned = displayed.pinned == true,
        applicationName = displayed.application?.name,
        reactions = displayed.emojiReactions.orEmpty().map {
            EmojiReaction(
                name = it.name,
                count = it.count,
                reactedByMe = it.me,
                imageUrl = it.url?.takeIf { url -> url.isNotBlank() }
                    ?: it.staticUrl?.takeIf { url -> url.isNotBlank() },
                accountIds = it.accountIds.toSet(),
                domain = it.domain,
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
        poll = displayed.poll?.let { poll ->
            io.github.ponpokoo.mastodonclient.domain.model.StatusPoll(
                id = poll.id,
                expiresAt = poll.expiresAt,
                expired = poll.expired,
                multiple = poll.multiple,
                votesCount = poll.votesCount,
                votersCount = poll.votersCount,
                voted = poll.voted,
                ownVotes = poll.ownVotes.toSet(),
                options = poll.options.map { option ->
                    io.github.ponpokoo.mastodonclient.domain.model.PollOption(
                        title = option.title,
                        votesCount = option.votesCount,
                    )
                },
            )
        },
        mediaAttachments = displayed.mediaAttachments.map {
            MediaAttachment(
                id = it.id,
                type = it.type,
                url = it.url,
                previewUrl = it.previewUrl,
                description = it.description,
                sensitive = displayed.sensitive,
                authorAvatarUrl = displayed.account.avatar.takeIf { _ -> it.type == "audio" }
                    ?.takeIf(String::isNotBlank),
                aspectRatio = it.meta?.let { meta ->
                    mediaAspectRatio(meta.original?.width, meta.original?.height, meta.original?.aspect)
                        ?: mediaAspectRatio(meta.width, meta.height, meta.aspect)
                        ?: mediaAspectRatio(meta.small?.width, meta.small?.height, meta.small?.aspect)
                },
            )
        },
    )
}

private fun mediaAspectRatio(width: Int?, height: Int?, aspect: Float?): Float? =
    if (width != null && height != null && width > 0 && height > 0) {
        width.toFloat() / height
    } else {
        aspect?.takeIf { it.isFinite() && it > 0f }
    }

private fun io.github.ponpokoo.mastodonclient.data.remote.dto.PollDto.toDomain() =
    io.github.ponpokoo.mastodonclient.domain.model.StatusPoll(
        id = id,
        expiresAt = expiresAt,
        expired = expired,
        multiple = multiple,
        votesCount = votesCount,
        votersCount = votersCount,
        voted = voted,
        ownVotes = ownVotes.toSet(),
        options = options.map { option ->
            io.github.ponpokoo.mastodonclient.domain.model.PollOption(option.title, option.votesCount)
        },
    )

private fun AccountDto.toDomain() = StatusAuthor(
    id = id,
    displayName = displayName.ifBlank { username },
    accountName = acct,
    avatarUrl = avatar,
    customEmojis = emojis.associate { it.shortcode to it.url },
    locked = locked,
)

internal fun nextAccountListCursor(linkHeader: String?): String? {
    val nextUrl = linkHeader?.let {
        Regex("""<([^>]+)>\s*;\s*rel="?next"?""", RegexOption.IGNORE_CASE)
            .find(it)?.groupValues?.getOrNull(1)
    } ?: return null
    val query = runCatching { java.net.URI(nextUrl).rawQuery }.getOrNull() ?: return null
    return query.split('&').firstOrNull { it.startsWith("max_id=") }
        ?.substringAfter('=')?.takeIf(String::isNotBlank)
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
}

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
