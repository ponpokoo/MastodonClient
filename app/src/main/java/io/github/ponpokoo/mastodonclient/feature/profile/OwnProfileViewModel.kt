package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.withAccountDisplay
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import java.util.UUID

/** Opaque cache generation, independent of whether account metadata could be persisted. */
internal fun newProfileImageRevision(): Long = UUID.randomUUID().mostSignificantBits

data class ProfileUiState(
    val profile: UserProfile? = null,
    val isLoadingProfile: Boolean = false,
    val isRefreshingProfile: Boolean = false,
    val profileError: String? = null,
    val editMessage: String? = null,
    val profileSelectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    val isLoadingMoreProfile: Boolean = false,
    val profileTabs: Map<ProfileStatusTab, ProfileTabUiState> = emptyMap(),
    val imageSessionKey: String? = null,
    val imageRefreshRevision: Long = 0,
)

class OwnProfileViewModel(private val timelineRepository: TimelineRepository, browsing: BrowsingSession,
    authRepository: io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository? = null,
    profileImageRepository: io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageRepository? = null,
) : SessionScopedViewModel(browsing) {
    private val imageEditor = ProfileImageEditor(profileImageRepository, viewModelScope)
    val profileImageEditState = imageEditor.state
    fun beginProfileEdit() = imageEditor.open()
    fun dismissProfileEdit() = imageEditor.dismiss()
    fun selectProfileImage(slot: ProfileImageSlot, uri: String) = imageEditor.select(slot, uri)
    override fun onCleared() { imageEditor.dismiss(); super.onCleared() }
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private var profileJob: Job? = null
    private val tabJobs = mutableMapOf<ProfileStatusTab, Job>()
    private var requested = false
    init {
        observeSession()
        if (authRepository != null) viewModelScope.launch {
            authRepository.observeSessions().collect { sessions ->
                val current = browsing.snapshot.value.account ?: return@collect
                val updated = sessions.firstOrNull { current.hasSameCredentials(it) } ?: return@collect
                _uiState.update { state ->
                    val profile = state.profile ?: return@update state
                    val author = profile.author.withAccountDisplay(updated)
                    state.copy(profile = profile.copy(author = author), imageRefreshRevision =
                        if (author.avatarRevision != profile.author.avatarRevision) newProfileImageRevision()
                        else state.imageRefreshRevision)
                }
            }
        }
    }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        imageEditor.dismiss()
        tabJobs.clear()
        _uiState.value = ProfileUiState(imageSessionKey = UUID.randomUUID().toString())
        if (requested && snapshot.account != null) loadProfile()
    }

    fun loadProfile() {
        requested = true
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isLoadingProfile || _uiState.value.profile != null) return
        profileJob = requestScope.launch {
            _uiState.update { it.copy(isLoadingProfile = true, profileError = null) }
            timelineRepository.getProfileHeader(session)
                .forSession(snapshot).onSuccess { profile ->
                    _uiState.update { it.copy(profile = profile, isLoadingProfile = false,
                        imageRefreshRevision = newProfileImageRevision()) }
                    loadTab(ProfileStatusTab.Posts, initialPosts = true)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProfile = false, profileError = error.message ?: "プロフィールを取得できませんでした")
                    }
                }
        }
    }

    fun refreshProfile() {
        if (_uiState.value.profile == null) return loadProfile()
        loadTab(_uiState.value.profileSelectedTab, refresh = true)
    }

    fun updateProfile(request: ProfileEditRequest) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account ?: return
        val save = imageEditor.beginSave(request) ?: return
        requestScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var success = false
            try {
                timelineRepository.updateProfile(session, save.request).forSession(snapshot).fold(
                    onSuccess = { updated ->
                        success = true
                        _uiState.update { state -> state.copy(
                            profile = state.profile?.copy(
                                author = updated.author,
                                headerUrl = updated.headerUrl,
                                noteHtml = updated.noteHtml,
                                locked = updated.locked,
                                fields = updated.fields,
                                customEmojis = updated.customEmojis,
                            ),
                            editMessage = "プロフィールを更新しました",
                            imageRefreshRevision = newProfileImageRevision(),
                        ) }
                    },
                    onFailure = { error -> _uiState.update {
                        it.copy(editMessage = error.message ?: "プロフィールを更新できませんでした")
                    } },
                )
            } finally { imageEditor.finishSave(save, success) }
        }
    }

    fun clearEditMessage() = _uiState.update { it.copy(editMessage = null) }

    fun selectProfileTab(tab: ProfileStatusTab) {
        currentSnapshot() ?: return
        if (_uiState.value.profile == null) return
        if (_uiState.value.profileSelectedTab == tab) return
        _uiState.update { it.copy(profileSelectedTab = tab).withTabs(it.profileTabs) }
        loadTab(tab)
    }

    fun prepareProfileTab(tab: ProfileStatusTab) = loadTab(tab)

    fun loadMoreProfile() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val state = _uiState.value
        val profile = state.profile ?: return
        val tab = state.profileSelectedTab
        val cached = state.profileTabs[tab] ?: return
        val cursor = cached.nextMaxId ?: return
        if (cached.isLoading || cached.isRefreshing || cached.isLoadingMore || cached.endReached) return
        updateTab(tab) { it.copy(isLoadingMore = true, error = null) }
        tabJobs[tab] = requestScope.launch {
            timelineRepository.getProfileStatuses(session, profile.author.id, tab, cursor)
                .forSession(snapshot).fold(
                    onSuccess = { page ->
                        updateTab(tab) { it.appendPage(page, cursor) }
                    },
                    onFailure = { error -> updateTab(tab) { it.copy(isLoadingMore = false, error = error.message) } },
                )
        }
    }

    private fun updateTab(tab: ProfileStatusTab, pinnedStatuses: List<TimelineStatus>? = null,
        update: (ProfileTabUiState) -> ProfileTabUiState) {
        _uiState.update { state ->
            val updated = if (pinnedStatuses != null) state.copy(profile = state.profile?.copy(pinnedStatuses = pinnedStatuses)) else state
            updated.withTabs(state.profileTabs + (tab to update(state.profileTabs[tab] ?: ProfileTabUiState())))
        }
    }

    private fun loadTab(tab: ProfileStatusTab, refresh: Boolean = false, initialPosts: Boolean = false) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val profile = _uiState.value.profile ?: return
        val cached = _uiState.value.profileTabs[tab] ?: ProfileTabUiState()
        if (cached.isLoading || cached.isRefreshing || (!refresh && (cached.isLoaded || cached.isLoadingMore))) return
        tabJobs[tab]?.cancel()
        updateTab(tab) { it.copy(isLoading = !refresh && !initialPosts,
            isLoadingMore = initialPosts, isRefreshing = refresh, error = null) }
        tabJobs[tab] = requestScope.launch {
            if (refresh) {
                val header = timelineRepository.getProfileHeader(session, profile.author.id).forSession(snapshot)
                    .getOrElse { error ->
                        updateTab(tab) { it.copy(isRefreshing = false, error = error.message ?: "プロフィールを更新できませんでした") }
                        return@launch
                    }
                _uiState.update { state -> state.copy(profile = header.copy(pinnedStatuses = state.profile?.pinnedStatuses.orEmpty()),
                    imageRefreshRevision = newProfileImageRevision())
                    .withTabs(state.profileTabs) }
            }
            val (result, pinnedResult) = fetchProfileTab(timelineRepository, session, profile.author.id, tab)
            result.forSession(snapshot)
            val pinnedStatuses = pinnedResult?.forSession(snapshot)?.getOrNull()
            result.fold(
                onSuccess = { page -> updateTab(tab, pinnedStatuses) { it.replacePage(page) } },
                onFailure = { error -> updateTab(tab, pinnedStatuses) { it.pageFailed(error) } },
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
        }).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(statuses = tab.statuses
            .filterNot { it.statusId == deleted }.map { status ->
                val updated = (change as? BrowsingSession.Change.StatusUpdated)?.status
                if (updated != null) status.withUpdatedActions(updated) else status
            }) }) }
    }
}
