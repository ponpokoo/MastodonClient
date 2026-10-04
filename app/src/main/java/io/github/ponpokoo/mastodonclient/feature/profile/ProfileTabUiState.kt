package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile

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
