package io.github.ponpokoo.transitionprototype

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow

private fun resource(name: String): String {
    val resourceId = when (name) {
        "sample_portrait" -> R.drawable.sample_portrait
        "sample_square" -> R.drawable.sample_square
        else -> R.drawable.sample_landscape
    }
    return "android.resource://io.github.ponpokoo.transitionprototype/$resourceId"
}
internal val PrototypeOwnAuthor = StatusAuthor("nagisa", "なぎさ", "nagisa@example.test", resource("sample_square"))
private val GalleryAuthor = StatusAuthor("haru", "はる", "haru@example.test", resource("sample_portrait"))
private fun image(id: String, name: String, description: String, ratio: Float) =
    MediaAttachment(id, "image", resource(name), resource(name), description, aspectRatio = ratio)
private fun post(id: String, author: StatusAuthor, text: String, media: List<MediaAttachment>, replies: Long, boosts: Long, favourites: Long) =
    TimelineStatus(id, id, "2026-10-11T00:00:00Z", author, null, text, "", false, "public", null,
        replies, boosts, favourites, mediaAttachments = media)

internal val PrototypeToots = listOf(
    post("single", PrototypeOwnAuthor,
        "<p>今日は湖のほとりを散歩してきました。<br>水面に映る山がきれいだったので、写真を一枚。</p>",
        listOf(image("lake", "sample_landscape", "山と湖のサンプル画像", 1.5f)), 3, 2, 12),
    post("gallery", GalleryAuthor,
        "<p>旅の写真をまとめました。<br>山の景色、夕暮れの塔、海辺の島。どの場所もまた訪れたいです。</p>",
        listOf(image("mountain", "sample_landscape", "山の景色のサンプル画像", 1.5f),
            image("tower", "sample_portrait", "夕暮れの塔のサンプル画像", 0.6f),
            image("island", "sample_square", "海辺の島のサンプル画像", 1f)), 1, 4, 8),
)

internal fun fixtureImageResource(media: MediaAttachment): Int = when (media.url) {
    resource("sample_portrait") -> R.drawable.sample_portrait
    resource("sample_square") -> R.drawable.sample_square
    else -> R.drawable.sample_landscape
}

/** Only this boundary replaces the production data sources. No production module dependency. */
internal class FixtureAuthRepository : AuthRepository {
    private val account = AccountSession("fixture", "https://example.test", "nagisa", "nagisa", "なぎさ",
        PrototypeOwnAuthor.avatarUrl, "fixture-only")
    private val sessions = MutableStateFlow(listOf(account))
    override fun observeSessions() = sessions
    override suspend fun restoreSession() = account
    override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> =
        Result.failure(UnsupportedOperationException("試作では固定アカウントを使います"))
    override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> =
        Result.failure(UnsupportedOperationException("試作では固定アカウントを使います"))
    override suspend fun logout() = Unit
}

internal class FixtureTimelineRepository : TimelineRepository {
    private var statuses = PrototypeToots
    private val relationships = mutableMapOf<String, AccountRelationship>()
    private fun page(items: List<TimelineStatus>, maxId: String?) =
        Result.success(TimelinePage(if (maxId == null) items else emptyList(), null, true))
    override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = page(statuses, maxId)
    override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int) = page(statuses, maxId)
    override suspend fun getAnnouncements(session: AccountSession) = Result.success(emptyList<ServerAnnouncement>())
    override suspend fun getProfile(session: AccountSession, accountId: String): Result<UserProfile> {
        val author = if (accountId == "haru") GalleryAuthor else PrototypeOwnAuthor
        return Result.success(UserProfile(author, resource("sample_landscape"),
            "<p>散歩と写真が好きです。よろしくお願いします。</p>", 128, 96, 2, emptyList(),
            endReached = true, isOwnProfile = accountId == session.accountId))
    }
    override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
        page(if (tab == ProfileStatusTab.Replies) emptyList() else statuses.filter { it.author.id == accountId }, maxId)
    override suspend fun getRelationship(session: AccountSession, accountId: String) =
        Result.success(relationships[accountId] ?: AccountRelationship())
    override suspend fun setFollowing(session: AccountSession, accountId: String, following: Boolean): Result<AccountRelationship> {
        val updated = (relationships[accountId] ?: AccountRelationship()).copy(following = following)
        relationships[accountId] = updated
        return Result.success(updated)
    }
    override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
        Result.success(NotificationPage(emptyList(), null, true))
    override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) = Result.success(ExplorePage())
    override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
        Result.success(SearchPage(SearchResults(emptyList(), emptyList(), emptyList()), null, true))
    override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) = update(statusId) {
        it.copy(favourited = favourite, favouritesCount = it.favouritesCount + if (it.favourited == favourite) 0 else if (favourite) 1 else -1)
    }
    override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean) = update(statusId) {
        it.copy(reblogged = reblogged, boostsCount = it.boostsCount + if (it.reblogged == reblogged) 0 else if (reblogged) 1 else -1)
    }
    override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) = update(statusId) { it.copy(bookmarked = bookmarked) }
    private fun update(id: String, transform: (TimelineStatus) -> TimelineStatus): Result<TimelineStatus> {
        val status = statuses.firstOrNull { it.statusId == id } ?: return Result.failure(IllegalArgumentException("投稿がありません"))
        val updated = transform(status)
        statuses = statuses.map { if (it.statusId == id) updated else it }
        return Result.success(updated)
    }
}
