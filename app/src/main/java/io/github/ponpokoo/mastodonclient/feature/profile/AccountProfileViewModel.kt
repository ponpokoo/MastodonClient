package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.common.AccountModerationMenu
import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.ponpokoo.mastodonclient.feature.common.PendingStatusAction
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.common.withStatusActionUpdate

data class AccountProfileUiState(
    val currentAccountId: String? = null,
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
    val profileTabs: Map<ProfileStatusTab, ProfileTabUiState> = emptyMap(),
    val instanceUrl: String? = null,
)

class AccountProfileViewModel(
    private val accountId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountProfileUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { session }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private var session: AccountSession? = null
    private val tabJobs = mutableMapOf<ProfileStatusTab, Job>()

    private val moderationMenu = AccountModerationMenu(timelineRepository, viewModelScope,
        session = { session }, context = { session }, isCurrent = { authRepository.restoreSession() == it },
        message = { message -> _uiState.update { it.copy(message = message) } },
        updated = { target, relationship -> if (target == accountId) _uiState.update { it.copy(relationship = relationship) } })
    val moderationMenuState = moderationMenu.state
    fun loadModerationMenu(target: String? = accountId) = moderationMenu.load(target)
    fun setProfileMuted(enabled: Boolean) = moderationMenu.mute(accountId, enabled)
    fun setProfileBlocked(enabled: Boolean) = moderationMenu.block(accountId, enabled)
    init {
        viewModelScope.launch {
            timelineRepository.moderation.collect { moderation ->
                val current = session ?: return@collect
                if (moderation.relationship(current, accountId)?.let { it.muting || it.blocking } == true) {
                    tabJobs.values.forEach { it.cancel() }
                    _uiState.update { it.withModeration(moderation, current) }
                }
            }
        }
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val current = session ?: return@collect
                if (update.sessionId == current.sessionId && update.instanceUrl == current.instanceUrl) {
                    _uiState.update { state -> state.copy(profile = state.profile?.let { profile ->
                        profile.copy(
                            statuses = profile.statuses.map { it.withStatusActionUpdate(update) },
                            pinnedStatuses = profile.pinnedStatuses.map { it.withStatusActionUpdate(update) },
                        )
                    }).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(
                        statuses = tab.statuses.map { it.withStatusActionUpdate(update) }) }) }
                }
            }
        }
        load()
    }
    fun retry() {
        if (_uiState.value.profile == null) load() else loadTab(_uiState.value.selectedTab, refresh = true)
    }

    fun refresh() {
        loadTab(_uiState.value.selectedTab, refresh = true)
    }

    fun selectTab(tab: ProfileStatusTab) {
        if (tab == _uiState.value.selectedTab) return
        if (_uiState.value.profile == null) return
        _uiState.update { it.copy(selectedTab = tab).withTabs(it.profileTabs) }
        loadTab(tab)
    }

    fun prepareTab(tab: ProfileStatusTab) = loadTab(tab)

    fun loadMore() {
        val current = session ?: return
        if (postsHidden(current)) return
        val state = _uiState.value
        val tab = state.selectedTab
        val cached = state.profileTabs[tab] ?: return
        val cursor = cached.nextMaxId ?: return
        if (cached.isLoading || cached.isRefreshing || cached.isLoadingMore || cached.endReached) return
        updateTab(tab) { it.copy(isLoadingMore = true, error = null) }
        tabJobs[tab] = viewModelScope.launch {
            val result = timelineRepository.getProfileStatuses(current, accountId, tab, cursor)
            currentCoroutineContext().ensureActive()
            result.fold(
                { page -> updateTab(tab) { it.copy(statuses = (it.statuses + page.statuses).distinctBy { status -> status.statusId },
                    nextMaxId = page.nextMaxId, endReached = page.endReached || page.nextMaxId == cursor, isLoadingMore = false) } },
                { error -> updateTab(tab) { it.copy(isLoadingMore = false, error = error.message) } },
            )
        }
    }

    private fun updateTab(tab: ProfileStatusTab, update: (ProfileTabUiState) -> ProfileTabUiState) {
        _uiState.update { it.withTabs(it.profileTabs + (tab to update(it.profileTabs[tab] ?: ProfileTabUiState()))) }
    }

    private fun loadTab(tab: ProfileStatusTab, refresh: Boolean = false, initialPosts: Boolean = false) {
        val current = session ?: return
        if (postsHidden(current)) return
        if (_uiState.value.profile == null) return
        val cached = _uiState.value.profileTabs[tab] ?: ProfileTabUiState()
        if (cached.isLoading || cached.isRefreshing || (!refresh && (cached.isLoaded || cached.isLoadingMore))) return
        tabJobs[tab]?.cancel()
        updateTab(tab) { it.copy(isLoading = !refresh && !initialPosts,
            isLoadingMore = initialPosts, isRefreshing = refresh, error = null) }
        tabJobs[tab] = viewModelScope.launch {
            if (refresh) {
                val result = timelineRepository.getProfileHeader(current, accountId)
                currentCoroutineContext().ensureActive()
                val header = result.getOrElse { error ->
                    updateTab(tab) { it.copy(isRefreshing = false, error = error.message ?: "プロフィールを更新できませんでした") }
                    return@launch
                }
                _uiState.update { state -> state.copy(profile = header.copy(pinnedStatuses = state.profile?.pinnedStatuses.orEmpty()))
                    .withTabs(state.profileTabs) }
            }
            val result = timelineRepository.getProfileStatuses(current, accountId, tab)
            currentCoroutineContext().ensureActive()
            result.fold(
                { page -> updateTab(tab) { it.copy(statuses = page.statuses, nextMaxId = page.nextMaxId,
                    endReached = page.endReached, isLoaded = true, isLoading = false, isLoadingMore = false, isRefreshing = false) } },
                { error -> updateTab(tab) { it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false,
                    error = error.message ?: "投稿を取得できませんでした") } },
            )
            if (tab == ProfileStatusTab.Posts) {
                val pinned = timelineRepository.getPinnedProfileStatuses(current, accountId)
                currentCoroutineContext().ensureActive()
                pinned.onSuccess { statuses -> _uiState.update { it.copy(profile = it.profile?.copy(pinnedStatuses = statuses)) } }
            }
        }
    }

    fun toggleFollow() = relationshipMutation { current, rel -> timelineRepository.setFollowing(current, accountId, !(rel.following || rel.requested)) }

    fun report(comment: String, forward: Boolean) {
        val current = session ?: return
        _uiState.update { it.copy(isMutating = true) }
        viewModelScope.launch { timelineRepository.reportAccount(current, accountId, comment, forward).fold(
            { _uiState.update { it.copy(isMutating = false, message = "報告を送信しました") } }, ::showError,
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
                        ).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(
                            statuses = tab.statuses.filterNot { it.statusId == status.statusId }) })
                    }
                },
                onFailure = ::showError,
            )
        }
    }

    fun unfollowStatus(status: TimelineStatus) = accountMutation {
        timelineRepository.setFollowing(it, status.author.id, false)
    }
    fun muteStatus(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.mute(status.author.id, enabled)
    fun blockStatus(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.block(status.author.id, enabled)
    fun reportStatus(status: TimelineStatus, comment: String) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.reportStatus(current, status.author.id, status.statusId, comment).fold(
                onSuccess = { _uiState.update { it.copy(message = "報告を送信しました") } },
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
        _uiState.update { it.copy(currentAccountId = current.accountId) }
        timelineRepository.getProfileHeader(current, accountId).fold({ profile ->
            _uiState.update { it.copy(profile = profile, instanceUrl = current.instanceUrl, isLoading = false, isLoadingMore = true) }
            if (!profile.isOwnProfile) viewModelScope.launch {
                timelineRepository.getRelationship(current, accountId).onSuccess { rel ->
                    moderationMenu.remember(accountId, rel)
                    _uiState.update { it.copy(relationship = rel) }
                }
            }
            loadTab(ProfileStatusTab.Posts, initialPosts = true)
        }, ::showError)
    }
    private fun postsHidden(current: AccountSession) =
        (timelineRepository.moderation.value.relationship(current, accountId) ?: _uiState.value.relationship)
            ?.let { it.muting || it.blocking } == true
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
            )).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(statuses = tab.statuses.map {
                if (it.statusId == original.statusId) updated else it
            }) })
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
