package io.github.ponpokoo.mastodonclient.feature.search

import io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed
import io.github.ponpokoo.mastodonclient.domain.model.ExplorePage
import io.github.ponpokoo.mastodonclient.domain.model.SearchException
import io.github.ponpokoo.mastodonclient.domain.model.SearchFailure

data class ExploreTabState(val page: ExplorePage? = null, val loading: Boolean = false,
    val loadingMore: Boolean = false, val error: String? = null, val errorIsPagination: Boolean = false)

data class ExploreUiState(val selectedFeed: ExploreFeed = ExploreFeed.Posts,
    val tabs: Map<ExploreFeed, ExploreTabState> = emptyMap(), val busyTags: Set<String> = emptySet(),
    val actionMessage: String? = null, val sessionKey: String? = null)

internal fun Throwable.exploreMessage(): String = when (this) {
    is UnsupportedOperationException -> "このサーバーはこの機能に対応していません。"
    is SearchException -> when (failure) {
        SearchFailure.Timeout -> "取得に時間がかかっています。時間内に結果を取得できませんでした。"
        SearchFailure.Authentication -> "必要な権限を確認できませんでした。アカウントの再認証をお試しください。"
        SearchFailure.RateLimited -> "要求回数の上限に達しました。少し待ってからお試しください。"
        SearchFailure.Rejected -> "サーバーが操作を受け付けませんでした。"
        else -> failure.message()
    }
    else -> "取得できませんでした。もう一度お試しください。"
}
