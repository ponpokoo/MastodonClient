package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable as runCatching
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.dto.AccountDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.domain.model.*
import retrofit2.HttpException

internal suspend fun loadSearchPage(
    apiClientFactory: ApiClientFactory,
    cacheStatus: (TimelineStatus) -> TimelineStatus,
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
                statuses = if (target == SearchTarget.Posts) result.statuses.map(StatusDto::toDomain).onEach { cacheStatus(it) } else emptyList(),
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

internal suspend fun loadExplorePage(apiClientFactory: ApiClientFactory, cacheStatus: (TimelineStatus) -> TimelineStatus, session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> = exploreRequest {
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
            ExploreFeed.Posts -> ExplorePage(statuses = api.getTrendingStatuses(limit, offset).map { cacheStatus(it.toDomain()) })
            ExploreFeed.Hashtags -> ExplorePage(tags = api.getTrendingTags(limit, offset).map { it.toSearchTag() })
            ExploreFeed.News -> ExplorePage(news = api.getTrendingLinks(limit, offset).map {
                ExploreNews(it.url, it.title, it.description, it.providerName, it.image?.takeIf(String::isNotBlank))
            })
            ExploreFeed.Followed -> error("Handled above")
        }
        page.copy(nextCursor = if (page.count() == 0) null else (offset + page.count()).toString())
    }
}

internal suspend fun loadExploreTag(apiClientFactory: ApiClientFactory, session: AccountSession, name: String): Result<SearchTag> = exploreRequest {
    apiClientFactory.create(session.instanceUrl, session.accessToken).getTag(name).toSearchTag()
}

internal suspend fun updateExploreTagFollowing(apiClientFactory: ApiClientFactory, session: AccountSession, name: String, following: Boolean): Result<SearchTag> = exploreRequest {
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
