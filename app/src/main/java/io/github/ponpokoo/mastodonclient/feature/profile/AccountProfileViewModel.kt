package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AccountProfileUiState(
    val profile: UserProfile? = null,
    val relationship: AccountRelationship? = null,
    val selectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isMutating: Boolean = false,
    val accountListTitle: String? = null,
    val accountList: List<StatusAuthor> = emptyList(),
    val message: String? = null,
    val errorMessage: String? = null,
)

class AccountProfileViewModel(
    private val accountId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountProfileUiState())
    val uiState: StateFlow<AccountProfileUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null

    init { load() }
    fun retry() = load()

    fun selectTab(tab: ProfileStatusTab) {
        if (tab == _uiState.value.selectedTab) return
        _uiState.update { it.copy(selectedTab = tab, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
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

    fun loadAccountList(followers: Boolean) {
        val current = session ?: return
        _uiState.update { it.copy(accountListTitle = if (followers) "フォロワー" else "フォロー中", accountList = emptyList()) }
        viewModelScope.launch { timelineRepository.getAccountList(current, accountId, followers).fold(
            { list -> _uiState.update { it.copy(accountList = list) } }, ::showError,
        ) }
    }
    fun closeAccountList() = _uiState.update { it.copy(accountListTitle = null, accountList = emptyList()) }
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
    fun toggleFavourite(status: TimelineStatus) = mutateStatus(status) { timelineRepository.setFavourite(it, status.statusId, !status.favourited) }
    fun toggleReblog(status: TimelineStatus) = mutateStatus(status) { timelineRepository.setReblogged(it, status.statusId, !status.reblogged) }
    fun toggleBookmark(status: TimelineStatus) = mutateStatus(status) { timelineRepository.setBookmarked(it, status.statusId, !status.bookmarked) }
    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus(status) { timelineRepository.setFedibirdReaction(it, status.statusId, emoji) }

    private fun load() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val current = authRepository.restoreSession() ?: run { _uiState.value = AccountProfileUiState(isLoading = false, errorMessage = "ログインが必要です"); return@launch }
        session = current
        timelineRepository.getProfile(current, accountId).fold({ profile ->
            _uiState.update { it.copy(profile = profile, isLoading = false) }
            if (!profile.isOwnProfile) timelineRepository.getRelationship(current, accountId).onSuccess { rel -> _uiState.update { it.copy(relationship = rel) } }
        }, ::showError)
    }
    private fun relationshipMutation(request: suspend (AccountSession, AccountRelationship) -> Result<AccountRelationship>) {
        val current = session ?: return; val relationship = _uiState.value.relationship ?: return
        _uiState.update { it.copy(isMutating = true) }
        viewModelScope.launch { request(current, relationship).fold({ rel -> _uiState.update { it.copy(relationship = rel, isMutating = false) } }, ::showError) }
    }
    private fun showError(error: Throwable) = _uiState.update { it.copy(isLoading = false, isLoadingMore = false, isMutating = false, errorMessage = error.message ?: "操作に失敗しました") }
    private fun mutateStatus(original: TimelineStatus, request: suspend (AccountSession) -> Result<TimelineStatus>) {
        val current = session ?: return
        viewModelScope.launch { request(current).onSuccess { updated -> _uiState.update { state -> state.copy(profile = state.profile?.copy(statuses = state.profile.statuses.map { if (it.statusId == original.statusId) updated else it })) } }.onFailure(::showError) }
    }
    class Factory(private val accountId: String, private val timelineRepository: TimelineRepository, private val authRepository: AuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountProfileViewModel(accountId, timelineRepository, authRepository) as T
    }
}
