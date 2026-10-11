package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ProfileTabUiState(
    val statuses: List<TimelineStatus> = emptyList(),
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

internal fun ProfileTabUiState.appendPage(page: TimelinePage, requestedCursor: String) = copy(
    statuses = (statuses + page.statuses).distinctBy(TimelineStatus::statusId),
    nextMaxId = page.nextMaxId, endReached = page.endReached || page.nextMaxId == requestedCursor,
    isLoadingMore = false,
)

internal fun ProfileTabUiState.replacePage(page: TimelinePage) = copy(
    statuses = page.statuses, nextMaxId = page.nextMaxId, endReached = page.endReached,
    isLoaded = true, isLoading = false, isLoadingMore = false, isRefreshing = false,
)

internal fun ProfileTabUiState.pageFailed(error: Throwable) = copy(
    isLoading = false, isLoadingMore = false, isRefreshing = false,
    error = error.message ?: "投稿を取得できませんでした",
)

/** Pinned failure stays independent of the posts result; session checks remain in the caller. */
internal suspend fun fetchProfileTab(
    repository: TimelineRepository, session: AccountSession, accountId: String, tab: ProfileStatusTab,
): Pair<Result<TimelinePage>, Result<List<TimelineStatus>>?> = coroutineScope {
    val posts = async { repository.getProfileStatuses(session, accountId, tab) }
    val pinned = if (tab == ProfileStatusTab.Posts) {
        async { repository.getPinnedProfileStatuses(session, accountId) }
    } else null
    posts.await() to pinned?.await()
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
