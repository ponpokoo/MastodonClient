package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: UserProfile? = null,
    val isLoadingProfile: Boolean = false,
    val isRefreshingProfile: Boolean = false,
    val profileError: String? = null,
    val profileSelectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    val isLoadingMoreProfile: Boolean = false,
)

class OwnProfileViewModel(private val timelineRepository: TimelineRepository, browsing: BrowsingSession) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState = _uiState.asStateFlow()
    private var profileJob: Job? = null
    private var requested = false
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        _uiState.value = ProfileUiState()
        if (requested && snapshot.account != null) loadProfile()
    }

    fun loadProfile() {
        requested = true
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isLoadingProfile || _uiState.value.profile != null) return
        profileJob = requestScope.launch {
            _uiState.update { it.copy(isLoadingProfile = true, profileError = null) }
            timelineRepository.getProfile(session)
                .forSession(snapshot).onSuccess { profile ->
                    _uiState.update { it.copy(profile = profile, isLoadingProfile = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProfile = false, profileError = error.message ?: "プロフィールを取得できませんでした")
                    }
                }
        }
    }

    fun refreshProfile() {
        val current = _uiState.value
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val existingProfile = current.profile ?: return loadProfile()
        if (current.isLoadingProfile || current.isRefreshingProfile) return
        profileJob?.cancel()
        profileJob = requestScope.launch {
            _uiState.update { it.copy(isRefreshingProfile = true, isLoadingMoreProfile = false, profileError = null) }
            val refreshedProfile = timelineRepository.getProfile(session, existingProfile.author.id)
                .forSession(snapshot).getOrElse { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshingProfile = false,
                            profileError = error.message ?: "プロフィールを更新できませんでした",
                        )
                    }
                    return@launch
                }
            val selectedTab = _uiState.value.profileSelectedTab
            val finalProfile = if (selectedTab == ProfileStatusTab.Posts) {
                refreshedProfile
            } else {
                timelineRepository.getProfileStatuses(session, refreshedProfile.author.id, selectedTab)
                    .forSession(snapshot).fold(
                        onSuccess = { page ->
                            refreshedProfile.copy(
                                statuses = page.statuses,
                                nextMaxId = page.nextMaxId,
                                endReached = page.endReached,
                            )
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isRefreshingProfile = false,
                                    profileError = error.message ?: "プロフィールを更新できませんでした",
                                )
                            }
                            return@launch
                        },
                    )
            }
            _uiState.update { it.copy(profile = finalProfile, isRefreshingProfile = false) }
        }
    }

    fun selectProfileTab(tab: ProfileStatusTab) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.profileSelectedTab == tab) return
        profileJob?.cancel()
        _uiState.update { it.copy(profileSelectedTab = tab, isLoadingProfile = true, isRefreshingProfile = false, isLoadingMoreProfile = false, profileError = null) }
        profileJob = requestScope.launch {
            timelineRepository.getProfileStatuses(session, profile.author.id, tab).forSession(snapshot).fold(
                onSuccess = { page ->
                    _uiState.update { state -> state.copy(
                        profile = state.profile?.copy(
                            statuses = page.statuses, nextMaxId = page.nextMaxId, endReached = page.endReached,
                        ),
                        isLoadingProfile = false,
                    ) }
                },
                onFailure = { error -> _uiState.update { it.copy(isLoadingProfile = false, profileError = error.message) } },
            )
        }
    }

    fun loadMoreProfile() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val state = _uiState.value
        val profile = state.profile ?: return
        val cursor = profile.nextMaxId ?: return
        if (state.isLoadingProfile || state.isRefreshingProfile || state.isLoadingMoreProfile || profile.endReached) return
        _uiState.update { it.copy(isLoadingMoreProfile = true) }
        profileJob = requestScope.launch {
            timelineRepository.getProfileStatuses(session, profile.author.id, state.profileSelectedTab, cursor)
                .forSession(snapshot).fold(
                    onSuccess = { page ->
                        _uiState.update { current -> current.copy(
                            profile = current.profile?.let { existing -> existing.copy(
                                statuses = (existing.statuses + page.statuses).distinctBy(TimelineStatus::statusId),
                                nextMaxId = page.nextMaxId,
                                endReached = page.endReached || page.nextMaxId == cursor,
                            ) },
                            isLoadingMoreProfile = false,
                        ) }
                    },
                    onFailure = { error -> _uiState.update { it.copy(isLoadingMoreProfile = false, profileError = error.message) } },
                )
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        _uiState.update { state -> state.copy(profile = state.profile?.let { profile ->
            val updated = (change as? BrowsingSession.Change.StatusUpdated)?.status
            profile.copy(
                statuses = profile.statuses.filterNot { it.statusId == deleted }.map { status ->
                    if (updated != null) status.withUpdatedActions(updated) else status
                },
                pinnedStatuses = when {
                    updated != null && updated.author.id == profile.author.id && updated.pinned ->
                        (listOf(updated) + profile.pinnedStatuses.filterNot { it.statusId == updated.statusId })
                    else -> profile.pinnedStatuses.filterNot {
                        it.statusId == deleted || (updated != null && it.statusId == updated.statusId && !updated.pinned)
                    }.map { if (updated != null) it.withUpdatedActions(updated) else it }
                },
            )
        }) }
    }
}
