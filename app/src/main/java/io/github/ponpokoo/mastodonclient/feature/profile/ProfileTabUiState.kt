package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ProfileTabUiState(
    val statuses: List<TimelineStatus> = emptyList(),
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val errorIsPagination: Boolean = false,
    val isPullRefreshing: Boolean = false,
    val isSlowLoading: Boolean = false,
) {
    val canAutoLoadMore: Boolean get() = isLoaded && !isLoading && !isRefreshing && !isLoadingMore &&
        error == null && !endReached && nextMaxId != null
}

internal fun ProfileTabUiState.appendPage(page: TimelinePage, requestedCursor: String) = copy(
    statuses = (statuses + page.statuses).distinctBy(TimelineStatus::statusId),
    nextMaxId = page.nextMaxId, endReached = page.endReached || page.nextMaxId == requestedCursor,
    isLoadingMore = false, isSlowLoading = false, error = null, errorIsPagination = false,
)

internal fun ProfileTabUiState.replacePage(page: TimelinePage) = copy(
    statuses = page.statuses, nextMaxId = page.nextMaxId, endReached = page.endReached,
    isLoaded = true, isLoading = false, isLoadingMore = false, isRefreshing = false,
    error = null, errorIsPagination = false,
    isPullRefreshing = false, isSlowLoading = false,
)

internal fun ProfileTabUiState.pageFailed(error: Throwable) = copy(
    isLoading = false, isLoadingMore = false, isRefreshing = false,
    error = error.message ?: "投稿を取得できませんでした",
    errorIsPagination = false,
    isPullRefreshing = false, isSlowLoading = false,
)

/** Pinned failure stays independent of the posts result; session checks remain in the caller. */
internal suspend fun fetchProfileTab(
    repository: TimelineRepository, session: AccountSession, accountId: String, tab: ProfileStatusTab,
    hashtag: String? = null,
    onSlowLoading: () -> Unit = {},
): Pair<Result<TimelinePage>, Result<List<TimelineStatus>>?> = coroutineScope {
    val posts = async { repository.profileStatuses(session, accountId, tab, hashtag, onSlowLoading = onSlowLoading) }
    val pinned = if (tab == ProfileStatusTab.Posts && hashtag == null) {
        async { repository.getPinnedProfileStatuses(session, accountId) }
    } else null
    posts.await() to pinned?.await()
}

internal suspend fun TimelineRepository.profileStatuses(session: AccountSession, accountId: String,
    tab: ProfileStatusTab, hashtag: String?, maxId: String? = null,
    onSlowLoading: () -> Unit = {},
): Result<TimelinePage> = coroutineScope {
    val noticeJob = if (hashtag != null) launch {
        delay(10_000)
        onSlowLoading()
    } else null
    try {
        if (hashtag == null) getProfileStatuses(session, accountId, tab, maxId)
        else getTaggedProfileStatuses(session, accountId, tab, hashtag, maxId)
    } finally {
        noticeJob?.cancel()
    }
}

internal fun UserProfile.withTab(tab: ProfileTabUiState) = copy(
    statuses = tab.statuses, nextMaxId = tab.nextMaxId, endReached = tab.endReached,
)

internal fun ProfileUiState.withTabs(tabs: Map<ProfileStatusTab, ProfileTabUiState>): ProfileUiState {
    if (profile == null) return copy(profileTabs = tabs)
    val selected = tabs[profileSelectedTab] ?: ProfileTabUiState()
    return copy(profileTabs = tabs, profile = profile?.withTab(selected),
        isLoadingProfile = selected.isLoading, isRefreshingProfile = selected.isRefreshing,
        isLoadingMoreProfile = selected.isLoadingMore, profileError = selected.error)
}

internal fun AccountProfileUiState.withTabs(tabs: Map<ProfileStatusTab, ProfileTabUiState>): AccountProfileUiState {
    if (profile == null) return copy(profileTabs = tabs)
    val selected = tabs[selectedTab] ?: ProfileTabUiState()
    return copy(profileTabs = tabs, profile = profile?.withTab(selected),
        isLoading = selected.isLoading, isRefreshing = selected.isRefreshing,
        isLoadingMore = selected.isLoadingMore, errorMessage = selected.error)
}
