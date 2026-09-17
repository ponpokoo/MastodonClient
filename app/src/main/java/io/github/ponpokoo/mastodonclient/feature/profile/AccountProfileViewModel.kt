package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import io.github.ponpokoo.mastodonclient.feature.common.PendingStatusAction
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.common.withStatusActionUpdate

data class AccountProfileUiState(
    val profile: UserProfile? = null,
    val relationship: AccountRelationship? = null,
    val selectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isMutating: Boolean = false,
    val lists: List<MastodonList> = emptyList(),
    val isLoadingLists: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

class AccountProfileViewModel(
    private val accountId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountProfileUiState())
    val uiState: StateFlow<AccountProfileUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null
    private var statusesJob: Job? = null

    init {
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val current = session ?: return@collect
                if (update.sessionId == current.sessionId && update.instanceUrl == current.instanceUrl) {
                    _uiState.update { state -> state.copy(profile = state.profile?.let { profile ->
                        profile.copy(
                            statuses = profile.statuses.map { it.withStatusActionUpdate(update) },
                            pinnedStatuses = profile.pinnedStatuses.map { it.withStatusActionUpdate(update) },
                        )
                    }) }
                }
            }
        }
        load()
    }
    fun retry() = load()

    fun refresh() {
        val current = session ?: return
        val existingProfile = _uiState.value.profile ?: return
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        statusesJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, isLoadingMore = false, errorMessage = null) }
            val refreshedProfile = timelineRepository.getProfileHeader(current, accountId).getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error.message ?: "プロフィールを更新できませんでした",
                    )
                }
                return@launch
            }
            _uiState.update { state -> state.copy(profile = refreshedProfile.copy(
                statuses = existingProfile.statuses, pinnedStatuses = existingProfile.pinnedStatuses,
                nextMaxId = existingProfile.nextMaxId, endReached = existingProfile.endReached,
            )) }
            val selectedTab = _uiState.value.selectedTab
            timelineRepository.getProfileStatuses(current, accountId, selectedTab).fold(
                onSuccess = { page -> _uiState.update { state -> state.copy(
                    profile = state.profile?.copy(statuses = page.statuses, nextMaxId = page.nextMaxId,
                        endReached = page.endReached), isRefreshing = false,
                ) } },
                onFailure = { error -> _uiState.update { it.copy(isRefreshing = false,
                    errorMessage = error.message ?: "プロフィールを更新できませんでした") } },
            )
            if (selectedTab == ProfileStatusTab.Posts) {
                timelineRepository.getPinnedProfileStatuses(current, accountId).onSuccess { pinned ->
                    _uiState.update { state -> state.copy(profile = state.profile?.copy(pinnedStatuses = pinned)) }
                }
            }
        }
    }

    fun selectTab(tab: ProfileStatusTab) {
        if (tab == _uiState.value.selectedTab) return
        statusesJob?.cancel()
        _uiState.update { it.copy(selectedTab = tab, isLoading = true, isLoadingMore = false, errorMessage = null) }
        statusesJob = viewModelScope.launch {
            val current = session ?: return@launch
            timelineRepository.getProfileStatuses(current, accountId, tab).fold(
                { page -> _uiState.update { state -> state.copy(profile = state.profile?.copy(statuses = page.statuses, nextMaxId = page.nextMaxId, endReached = page.endReached), isLoading = false) } }, ::showError,
            )
        }
    }

    fun loadMore() {
        val current = session ?: return
        val state = _uiState.value
        val profile = state.profile ?: return
        if (state.isLoadingMore || profile.endReached || profile.nextMaxId == null) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch { timelineRepository.getProfileStatuses(current, accountId, state.selectedTab, profile.nextMaxId).fold(
            { page -> _uiState.update { old -> old.copy(profile = old.profile?.copy(statuses = (old.profile.statuses + page.statuses).distinctBy { it.statusId }, nextMaxId = page.nextMaxId, endReached = page.endReached), isLoadingMore = false) } }, ::showError,
        ) }
    }

    fun toggleFollow() = relationshipMutation { current, rel -> timelineRepository.setFollowing(current, accountId, !(rel.following || rel.requested)) }
    fun toggleMute() = relationshipMutation { current, rel -> timelineRepository.setMuted(current, accountId, !rel.muting) }
    fun toggleBlock() = relationshipMutation { current, rel -> timelineRepository.setBlocked(current, accountId, !rel.blocking) }

    fun report(comment: String, forward: Boolean) {
        val current = session ?: return
        _uiState.update { it.copy(isMutating = true) }
        viewModelScope.launch { timelineRepository.reportAccount(current, accountId, comment, forward).fold(
            { _uiState.update { it.copy(isMutating = false, message = "通報を送信しました") } }, ::showError,
        ) }
    }
    fun updateProfile(request: ProfileEditRequest) {
        val current = session ?: return
        _uiState.update { it.copy(isMutating = true) }
        viewModelScope.launch { timelineRepository.updateProfile(current, request).fold(
            { updated -> _uiState.update { old -> old.copy(profile = old.profile?.copy(author = updated.author, noteHtml = updated.noteHtml, locked = updated.locked, fields = updated.fields), isMutating = false, message = "プロフィールを更新しました") } }, ::showError,
        ) }
    }
    fun clearMessage() = _uiState.update { it.copy(message = null, errorMessage = null) }
    fun toggleFavourite(status: TimelineStatus) =
        runOptimisticAction(status, statusActionManager::beginFavourite)
    fun toggleReblog(status: TimelineStatus) =
        runOptimisticAction(status, statusActionManager::beginReblog)
    fun toggleBookmark(status: TimelineStatus) = mutateStatus(status) { timelineRepository.setBookmarked(it, status.statusId, !status.bookmarked) }
    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus(status) { timelineRepository.setFedibirdReaction(it, status.statusId, emoji) }
    fun setPinned(status: TimelineStatus) = mutateStatus(status) {
        timelineRepository.setPinned(it, status.statusId, !status.pinned)
    }

    fun deleteStatus(status: TimelineStatus) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.deleteStatus(current, status.statusId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            profile = state.profile?.copy(
                                statuses = state.profile.statuses.filterNot { it.statusId == status.statusId },
                                pinnedStatuses = state.profile.pinnedStatuses.filterNot { it.statusId == status.statusId },
                            ),
                            message = "投稿を削除しました",
                        )
                    }
                },
                onFailure = ::showError,
            )
        }
    }

    fun unfollowStatus(status: TimelineStatus) = accountMutation {
        timelineRepository.setFollowing(it, status.author.id, false)
    }
    fun muteStatus(status: TimelineStatus) = accountMutation {
        timelineRepository.setMuted(it, status.author.id, true)
    }
    fun blockStatus(status: TimelineStatus) = accountMutation {
        timelineRepository.setBlocked(it, status.author.id, true)
    }
    fun reportStatus(status: TimelineStatus, comment: String) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.reportStatus(current, status.author.id, status.statusId, comment).fold(
                onSuccess = { _uiState.update { it.copy(message = "通報を送信しました") } },
                onFailure = ::showError,
            )
        }
    }
    fun loadLists() {
        val current = session ?: return
        if (_uiState.value.isLoadingLists) return
        _uiState.update { it.copy(isLoadingLists = true) }
        viewModelScope.launch {
            timelineRepository.getLists(current).fold(
                onSuccess = { lists -> _uiState.update { it.copy(lists = lists, isLoadingLists = false) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingLists = false) }
                    showError(error)
                },
            )
        }
    }
    fun addToList(status: TimelineStatus, listId: String) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.addAccountToList(current, listId, status.author.id).fold(
                onSuccess = { _uiState.update { it.copy(message = "リストに追加しました") } },
                onFailure = ::showError,
            )
        }
    }

    fun addProfileToList(listId: String) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.addAccountToList(current, listId, accountId).fold(
                onSuccess = { _uiState.update { it.copy(message = "リストに追加しました") } },
                onFailure = ::showError,
            )
        }
    }

    private fun load() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val current = authRepository.restoreSession() ?: run { _uiState.value = AccountProfileUiState(isLoading = false, errorMessage = "ログインが必要です"); return@launch }
        session = current
        timelineRepository.getProfileHeader(current, accountId).fold({ profile ->
            _uiState.update { it.copy(profile = profile, isLoading = false, isLoadingMore = true) }
            if (!profile.isOwnProfile) viewModelScope.launch {
                timelineRepository.getRelationship(current, accountId).onSuccess { rel ->
                    _uiState.update { it.copy(relationship = rel) }
                }
            }
            statusesJob = viewModelScope.launch {
                timelineRepository.getProfileStatuses(current, accountId, ProfileStatusTab.Posts).fold(
                    onSuccess = { page -> _uiState.update { state -> state.copy(
                        profile = state.profile?.copy(statuses = page.statuses, nextMaxId = page.nextMaxId,
                            endReached = page.endReached), isLoadingMore = false,
                    ) } },
                    onFailure = { error -> _uiState.update { it.copy(isLoadingMore = false,
                        errorMessage = error.message ?: "投稿を取得できませんでした") } },
                )
            }
            viewModelScope.launch {
                timelineRepository.getPinnedProfileStatuses(current, accountId).onSuccess { pinned ->
                    _uiState.update { state -> state.copy(profile = state.profile?.copy(pinnedStatuses = pinned)) }
                }
            }
        }, ::showError)
    }
    private fun relationshipMutation(request: suspend (AccountSession, AccountRelationship) -> Result<AccountRelationship>) {
        val current = session ?: return; val relationship = _uiState.value.relationship ?: return
        _uiState.update { it.copy(isMutating = true) }
        viewModelScope.launch { request(current, relationship).fold({ rel -> _uiState.update { it.copy(relationship = rel, isMutating = false) } }, ::showError) }
    }
    private fun accountMutation(request: suspend (AccountSession) -> Result<AccountRelationship>) {
        val current = session ?: return
        viewModelScope.launch {
            request(current).fold(
                onSuccess = { relationship -> _uiState.update { it.copy(relationship = relationship) } },
                onFailure = ::showError,
            )
        }
    }
    private fun showError(error: Throwable) = _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, isMutating = false, errorMessage = error.message ?: "操作に失敗しました") }
    private fun mutateStatus(original: TimelineStatus, request: suspend (AccountSession) -> Result<TimelineStatus>) {
        val current = session ?: return
        viewModelScope.launch { request(current).onSuccess { updated -> _uiState.update { state ->
            val profile = state.profile
            state.copy(profile = profile?.copy(
                statuses = profile.statuses.map { if (it.statusId == original.statusId) updated else it },
                pinnedStatuses = when {
                    updated.pinned -> (listOf(updated) + profile.pinnedStatuses).distinctBy { it.statusId }
                    else -> profile.pinnedStatuses.filterNot { it.statusId == original.statusId }
                },
            ))
        } }.onFailure(::showError) }
    }

    private fun runOptimisticAction(
        status: TimelineStatus,
        begin: (AccountSession, TimelineStatus) -> PendingStatusAction?,
    ) {
        val current = session ?: return
        val pending = begin(current, status) ?: return
        viewModelScope.launch {
            statusActionManager.complete(pending).onFailure(::showError)
        }
    }

    class Factory(
        private val accountId: String,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
        private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountProfileViewModel(accountId, timelineRepository, authRepository, statusActionManager) as T
    }
}
