package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.AccountModerationMenu
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.common.PendingStatusAction
import io.github.ponpokoo.mastodonclient.feature.common.withStatusActionUpdate
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.repository.AccountAvatarRepository
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Headers retain their existing cache generation, independently of avatar revalidation. */
internal fun newProfileHeaderRevision(): Long = UUID.randomUUID().mostSignificantBits

data class ProfileUiState(
    val currentAccountId: String? = null,
    val instanceUrl: String? = null,
    val relationship: AccountRelationship? = null,
    val isMutating: Boolean = false,
    val lists: List<MastodonList> = emptyList(),
    val isLoadingLists: Boolean = false,
    val message: String? = null,
    val profile: UserProfile? = null,
    val isLoadingProfile: Boolean = false,
    val isRefreshingProfile: Boolean = false,
    val profileError: String? = null,
    val profileSelectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    val isLoadingMoreProfile: Boolean = false,
    val profileTabs: Map<ProfileStatusTab, ProfileTabUiState> = emptyMap(),
    val imageSessionKey: String? = null,
    val headerRefreshRevision: Long = 0,
    val featuredTags: List<io.github.ponpokoo.mastodonclient.domain.model.FeaturedTag> = emptyList(),
    val featuredTagsError: String? = null,
    val selectedHashtag: String? = null,
    val supportsReactionHistory: Boolean = false,
)

/** One implementation, with an independent instance for each navigation entry.
 * A null accountId follows the active account; an explicit ID is scoped to that session's server.
 */
class ProfileViewModel(private val timelineRepository: TimelineRepository, browsing: BrowsingSession,
    authRepository: io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository? = null,
    profileImageRepository: io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageRepository? = null,
    private val accountId: String? = null,
    private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
    private val avatarRepository: AccountAvatarRepository? = null,
) : SessionScopedViewModel(browsing) {
    private val imageEditor = ProfileImageEditor(profileImageRepository, viewModelScope)
    val profileImageEditState = imageEditor.state
    fun beginProfileEdit() { if (_uiState.value.profile?.isOwnProfile == true) imageEditor.open() }
    fun dismissProfileEdit() = imageEditor.dismiss()
    fun selectProfileImage(slot: ProfileImageSlot, uri: String) = imageEditor.select(slot, uri)
    override fun onCleared() { imageEditor.dismiss() }
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private var profileJob: Job? = null
    private var reactionHistorySupportJob: Job? = null
    private val tabJobs = mutableMapOf<ProfileStatusTab, Job>()
    private var postsGeneration = 0L
    private var featuredTagsJob: Job? = null
    private var featuredTagsGeneration = 0L
    private var metadataRevision = 0L
    private var requested = accountId != null
    private var targetInstanceUrl: String? = null
    private val moderationMenu = AccountModerationMenu(timelineRepository, requestScope,
        session = { currentSnapshot()?.account }, context = { currentSnapshot() },
        isCurrent = { authRepository == null || it.hasSameCredentials(authRepository.restoreSession()) },
        message = { message -> _uiState.update { it.copy(message = message) } },
        updated = { target, relationship ->
            if (target == _uiState.value.profile?.author?.id) _uiState.update { it.copy(relationship = relationship) }
        })
    val moderationMenuState = moderationMenu.state
    fun loadModerationMenu(target: String? = _uiState.value.profile?.author?.id) = moderationMenu.load(target)
    fun setProfileMuted(enabled: Boolean) { _uiState.value.profile?.author?.id?.let { moderationMenu.mute(it, enabled) } }
    fun setProfileBlocked(enabled: Boolean) { _uiState.value.profile?.author?.id?.let { moderationMenu.block(it, enabled) } }

    init {
        observeSession()
        if (authRepository != null) viewModelScope.launch {
            authRepository.observeSessions().collect { sessions ->
                val current = browsing.snapshot.value.account ?: return@collect
                val updated = sessions.firstOrNull { current.hasSameCredentials(it) } ?: return@collect
                _uiState.update { state ->
                    val profile = state.profile?.takeIf { it.isOwnProfile } ?: return@update state
                    val author = profile.author.withAccountDisplay(updated)
                    state.copy(profile = profile.copy(author = author))
                }
            }
        }
        viewModelScope.launch {
            timelineRepository.moderation.collect { moderation ->
                val snapshot = currentSnapshot() ?: return@collect
                val target = _uiState.value.profile?.author?.id ?: return@collect
                if (moderation.relationship(snapshot.account!!, target)?.let { it.muting || it.blocking } == true) {
                    postsGeneration++
                    tabJobs.values.forEach { it.cancel() }
                    _uiState.update { it.withModeration(moderation, snapshot.account) }
                }
            }
        }
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val snapshot = currentSnapshot() ?: return@collect
                val session = snapshot.account!!
                if (update.sessionId == session.sessionId && update.instanceUrl == session.instanceUrl) {
                    _uiState.update { state -> state.copy(profile = state.profile?.let { profile ->
                        profile.copy(statuses = profile.statuses.map { it.withStatusActionUpdate(update) },
                            pinnedStatuses = profile.pinnedStatuses.map { it.withStatusActionUpdate(update) })
                    }).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(
                        statuses = tab.statuses.map { it.withStatusActionUpdate(update) }) }) }
                }
            }
        }
        if (accountId != null) io.github.ponpokoo.mastodonclient.feature.common.observeServerFilterChanges(
            viewModelScope, timelineRepository, { currentSnapshot()?.account }, "account:$accountId",
        ) {
            tabJobs.values.toList().forEach { it.join() }
            loadTab(_uiState.value.profileSelectedTab, refresh = true, userInitiated = false)
            tabJobs.values.toList().forEach { it.join() }
            _uiState.value.profileTabs[_uiState.value.profileSelectedTab]?.error == null
        }
    }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        reactionHistorySupportJob = null
        imageEditor.dismiss()
        moderationMenu.reset()
        metadataRevision++
        postsGeneration++
        featuredTagsGeneration++
        tabJobs.clear()
        _uiState.value = ProfileUiState(imageSessionKey = UUID.randomUUID().toString(),
            currentAccountId = snapshot.account?.accountId, instanceUrl = snapshot.account?.instanceUrl)
        if (accountId != null && snapshot.account != null) {
            val instance = snapshot.account.instanceUrl.trimEnd('/')
            if (targetInstanceUrl == null) targetInstanceUrl = instance
            if (targetInstanceUrl != instance) {
                _uiState.update { it.copy(profileError = "閲覧アカウントが変更されました。プロフィールを開き直してください") }
                return
            }
        }
        snapshot.account?.let { account -> requestScope.launch {
            timelineRepository.observeServerCapabilities(account).collect { capabilities ->
                Result.success(capabilities).forSession(snapshot)
                _uiState.update { it.copy(supportsReactionHistory = capabilities.supportsReactionHistory) }
            }
        } }
        if (requested && snapshot.account != null) loadProfile()
    }

    fun loadProfile() {
        requested = true
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (accountId != null && targetInstanceUrl != session.instanceUrl.trimEnd('/')) return
        if (_uiState.value.isLoadingProfile || _uiState.value.profile != null) return
        profileJob = requestScope.launch {
            val revision = metadataRevision
            _uiState.update { it.copy(isLoadingProfile = true, profileError = null) }
            timelineRepository.getProfileHeader(session, accountId ?: session.accountId)
                .forSession(snapshot).onSuccess { response ->
                    val profile = _uiState.value.profile?.takeIf { revision != metadataRevision } ?: response
                    _uiState.update { it.copy(profile = profile, isLoadingProfile = false,
                        headerRefreshRevision = if (profile.isOwnProfile) newProfileHeaderRevision() else 0) }
                    if (profile.isOwnProfile) loadReactionHistorySupport()
                    if (!profile.isOwnProfile) requestScope.launch {
                        timelineRepository.getRelationship(session, profile.author.id).forSession(snapshot).onSuccess { relation ->
                            moderationMenu.remember(profile.author.id, relation)
                            _uiState.update { it.copy(relationship = relation) }
                        }
                    }
                    loadTab(ProfileStatusTab.Posts, initialPosts = true)
                    retryFeaturedTags()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProfile = false, profileError = error.message ?: "プロフィールを取得できませんでした")
                    }
                }
        }
    }

    fun loadReactionHistorySupport() {
        val snapshot = currentSnapshot() ?: return
        if (_uiState.value.profile?.isOwnProfile != true || reactionHistorySupportJob?.isActive == true) return
        reactionHistorySupportJob = requestScope.launch {
            timelineRepository.getServerCapabilities(snapshot.account!!).forSession(snapshot).onSuccess { capabilities ->
                _uiState.update { it.copy(supportsReactionHistory = capabilities.supportsReactionHistory) }
            }
        }
    }

    fun refreshProfile() {
        val state = _uiState.value
        val profile = state.profile ?: return loadProfile()
        if (state.profileTabs[state.profileSelectedTab]?.isRefreshing == true) return
        if (profile.isOwnProfile) currentSnapshot()?.let { revalidateAvatar(it, profile.author.avatarUrl) }
        if (_uiState.value.selectedHashtag != null) resetHashtag(null)
        retryFeaturedTags()
        loadTab(_uiState.value.profileSelectedTab, refresh = true)
    }

    private fun revalidateAvatar(snapshot: BrowsingSession.Snapshot, url: String) {
        val repository = avatarRepository ?: return
        if (url.isBlank()) return
        requestScope.launch {
            Result.success(Unit).forSession(snapshot)
            // Image failure must not turn successful metadata synchronization into an error.
            runCatchingCancellable { repository.revalidate(url) }.forSession(snapshot)
        }
    }

    fun retryFeaturedTags() {
        val snapshot = currentSnapshot() ?: return
        val accountId = _uiState.value.profile?.author?.id ?: return
        val generation = ++featuredTagsGeneration
        featuredTagsJob?.cancel()
        _uiState.update { it.copy(featuredTagsError = null) }
        featuredTagsJob = requestScope.launch {
            val result = timelineRepository.getFeaturedTags(snapshot.account!!, accountId).forSession(snapshot)
            if (generation != featuredTagsGeneration) return@launch
            result.fold(
                onSuccess = { tags -> _uiState.update { it.copy(featuredTags = tags) } },
                onFailure = { error -> if (error !is UnsupportedOperationException) _uiState.update {
                    it.copy(featuredTagsError = "注目のハッシュタグを取得できませんでした")
                } },
            )
        }
    }

    fun selectHashtag(name: String) {
        currentSnapshot() ?: return
        if (_uiState.value.featuredTags.none { it.name == name }) return
        resetHashtag(name.takeUnless { it == _uiState.value.selectedHashtag })
        loadTab(_uiState.value.profileSelectedTab)
    }

    private fun resetHashtag(name: String?) {
        postsGeneration++
        tabJobs.values.forEach { it.cancel() }
        tabJobs.clear()
        _uiState.update { it.copy(selectedHashtag = name,
            profile = it.profile?.copy(statuses = emptyList(), pinnedStatuses = emptyList(), nextMaxId = null, endReached = false),
        ).withTabs(emptyMap()) }
    }

    fun updateProfile(request: ProfileEditRequest) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account ?: return
        if (_uiState.value.profile?.isOwnProfile != true) return
        val save = imageEditor.beginSave(request) ?: return
        requestScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var success = false
            try {
                timelineRepository.updateProfile(session, save.request).forSession(snapshot).fold(
                    onSuccess = { updated ->
                        success = true
                        if (save.request.avatarFilePath != null) revalidateAvatar(snapshot, updated.author.avatarUrl)
                        browsing.publish(snapshot, BrowsingSession.Change.ProfileUpdated(updated))
                        _uiState.update { it.copy(message = "プロフィールを更新しました") }
                    },
                    onFailure = { error -> _uiState.update {
                        it.copy(message = error.message ?: "プロフィールを更新できませんでした")
                    } },
                )
            } finally { imageEditor.finishSave(save, success) }
        }
    }

    fun selectProfileTab(tab: ProfileStatusTab) {
        currentSnapshot() ?: return
        if (_uiState.value.profile == null) return
        if (_uiState.value.profileSelectedTab == tab) return
        if (_uiState.value.selectedHashtag != null) resetHashtag(null)
        _uiState.update { it.copy(profileSelectedTab = tab).withTabs(it.profileTabs) }
        loadTab(tab)
    }

    fun prepareProfileTab(tab: ProfileStatusTab) = loadTab(tab)

    fun retryProfile() {
        if (_uiState.value.profileTabs[_uiState.value.profileSelectedTab]?.errorIsPagination == true) loadMoreProfile()
        else if (_uiState.value.selectedHashtag != null) loadTab(_uiState.value.profileSelectedTab, refresh = true)
        else refreshProfile()
    }

    fun loadMoreProfile() = loadMoreProfile(automatic = false)
    fun loadMoreProfileAutomatically() = loadMoreProfile(automatic = true)

    private fun loadMoreProfile(automatic: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val state = _uiState.value
        val profile = state.profile ?: return
        if (postsHidden(session)) return
        val tab = state.profileSelectedTab
        val generation = postsGeneration
        val hashtag = state.selectedHashtag
        val cached = state.profileTabs[tab] ?: return
        val cursor = cached.nextMaxId ?: return
        if (cached.isLoading || cached.isRefreshing || cached.isLoadingMore || cached.endReached ||
            automatic && !cached.canAutoLoadMore) return
        updateTab(tab) { it.copy(isLoadingMore = true, isSlowLoading = false, error = null, errorIsPagination = false) }
        tabJobs[tab] = requestScope.launch {
            val result = timelineRepository.profileStatuses(session, profile.author.id, tab, hashtag, cursor) {
                if (generation == postsGeneration) updateTab(tab) { it.copy(isSlowLoading = true) }
            }.forSession(snapshot)
            if (generation != postsGeneration) return@launch
            result.fold(
                    onSuccess = { page ->
                        updateTab(tab) { it.appendPage(page, cursor) }
                    },
                    onFailure = { error -> updateTab(tab) { it.copy(isLoadingMore = false, isSlowLoading = false,
                        error = error.message ?: "投稿の続きを取得できませんでした", errorIsPagination = true) } },
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

    private fun loadTab(tab: ProfileStatusTab, refresh: Boolean = false, initialPosts: Boolean = false,
        userInitiated: Boolean = true) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val profile = _uiState.value.profile ?: return
        if (postsHidden(session)) return
        val generation = postsGeneration
        val hashtag = _uiState.value.selectedHashtag
        val cached = _uiState.value.profileTabs[tab] ?: ProfileTabUiState()
        if (cached.isLoading || cached.isRefreshing || (!refresh && (cached.isLoaded || cached.isLoadingMore))) return
        tabJobs[tab]?.cancel()
        updateTab(tab) { it.copy(isLoading = !refresh && !initialPosts,
            isLoadingMore = initialPosts, isRefreshing = refresh, isPullRefreshing = refresh && cached.isLoaded && userInitiated,
            isSlowLoading = false, error = null, errorIsPagination = false) }
        tabJobs[tab] = requestScope.launch {
            if (refresh && hashtag == null) {
                val revision = metadataRevision
                val header = timelineRepository.getProfileHeader(session, profile.author.id).forSession(snapshot)
                    .getOrElse { error ->
                        updateTab(tab) { it.copy(isRefreshing = false, isPullRefreshing = false,
                            error = error.message ?: "プロフィールを更新できませんでした") }
                        return@launch
                    }
                if (generation != postsGeneration) return@launch
                if (revision == metadataRevision && header.isOwnProfile && userInitiated &&
                    header.author.avatarUrl != profile.author.avatarUrl) {
                    revalidateAvatar(snapshot, header.author.avatarUrl)
                }
                if (revision == metadataRevision) _uiState.update { state -> state.copy(profile = header.copy(pinnedStatuses = state.profile?.pinnedStatuses.orEmpty()),
                    headerRefreshRevision = if (header.isOwnProfile) newProfileHeaderRevision() else state.headerRefreshRevision)
                    .withTabs(state.profileTabs) }
            }
            val (result, pinnedResult) = fetchProfileTab(timelineRepository, session, profile.author.id, tab, hashtag) {
                if (generation == postsGeneration) updateTab(tab) { it.copy(isSlowLoading = true) }
            }
            result.forSession(snapshot)
            currentCoroutineContext().ensureActive()
            if (generation != postsGeneration) return@launch
            val pinnedStatuses = pinnedResult?.forSession(snapshot)?.getOrNull()
            result.fold(
                onSuccess = { page -> updateTab(tab, pinnedStatuses) { it.replacePage(page) } },
                onFailure = { error -> updateTab(tab, pinnedStatuses) { it.pageFailed(error) } },
            )
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (change is BrowsingSession.Change.ProfileUpdated) {
            val updated = change.profile
            val target = accountId ?: currentSnapshot()?.account?.accountId
            if (target != updated.author.id) return
            metadataRevision++
            _uiState.update { state ->
                val current = state.profile
                state.copy(profile = updated.copy(statuses = current?.statuses.orEmpty(),
                    pinnedStatuses = current?.pinnedStatuses.orEmpty(), nextMaxId = current?.nextMaxId,
                    endReached = current?.endReached ?: false), headerRefreshRevision = newProfileHeaderRevision())
            }
            return
        }
        if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.FiltersChanged) {
            // Explicit profiles observe repository filter changes; the main tab receives stream events.
            if (accountId != null) return
            val snapshot = currentSnapshot() ?: return
            requestScope.launch {
                io.github.ponpokoo.mastodonclient.feature.common.trackServerFilterDisplay(timelineRepository, snapshot.account!!, "own-profile") {
                    profileJob?.join(); tabJobs.values.toList().forEach { it.join() }
                    loadTab(_uiState.value.profileSelectedTab, refresh = true, userInitiated = false)
                    profileJob?.join(); tabJobs.values.toList().forEach { it.join() }
                    _uiState.value.profileError == null && _uiState.value.profileTabs[_uiState.value.profileSelectedTab]?.error == null
                }
            }
            return
        }
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        _uiState.update { state -> state.copy(profile = state.profile?.let { profile ->
            val updated = (change as? BrowsingSession.Change.StatusUpdated)?.status
            profile.copy(
                statuses = profile.statuses.filterNot { it.statusId == deleted }.map { status ->
                    if (updated != null) status.withUpdatedActions(updated).copy(poll = updated.poll) else status
                },
                pinnedStatuses = when {
                    updated != null && updated.author.id == profile.author.id && updated.pinned ->
                        (listOf(updated) + profile.pinnedStatuses.filterNot { it.statusId == updated.statusId })
                    else -> profile.pinnedStatuses.filterNot {
                        it.statusId == deleted || (updated != null && it.statusId == updated.statusId && !updated.pinned)
                    }.map { if (updated != null) it.withUpdatedActions(updated).copy(poll = updated.poll) else it }
                },
            )
        }).withTabs(state.profileTabs.mapValues { (_, tab) -> tab.copy(statuses = tab.statuses
            .filterNot { it.statusId == deleted }.map { status ->
                val updated = (change as? BrowsingSession.Change.StatusUpdated)?.status
                if (updated != null) status.withUpdatedActions(updated).copy(poll = updated.poll) else status
            }) }) }
    }

    private fun postsHidden(session: AccountSession): Boolean {
        val target = _uiState.value.profile?.author?.id ?: return false
        val relation = timelineRepository.moderation.value.relationship(session, target) ?: _uiState.value.relationship
        return relation?.let { it.muting || it.blocking } == true
    }

    fun toggleFollow() {
        val snapshot = currentSnapshot() ?: return
        val profile = _uiState.value.profile ?: return
        if (profile.isOwnProfile || _uiState.value.isMutating) return
        val relation = _uiState.value.relationship ?: return
        _uiState.update { it.copy(isMutating = true) }
        requestScope.launch {
            timelineRepository.setFollowing(snapshot.account!!, profile.author.id, !(relation.following || relation.requested))
                .forSession(snapshot).fold(
                    { updated ->
                        moderationMenu.remember(profile.author.id, updated)
                        _uiState.update { it.copy(relationship = updated, isMutating = false) }
                    }, { showActionError(it); _uiState.update { state -> state.copy(isMutating = false) } },
                )
        }
    }

    fun report(comment: String, forward: Boolean) {
        val profile = _uiState.value.profile ?: return
        if (profile.isOwnProfile) return
        accountRequest("報告を送信しました") { timelineRepository.reportAccount(it, profile.author.id, comment, forward) }
    }

    fun toggleFavourite(status: TimelineStatus) = optimisticAction(status, statusActionManager::beginFavourite)
    fun toggleReblog(status: TimelineStatus) = optimisticAction(status, statusActionManager::beginReblog)
    fun toggleBookmark(status: TimelineStatus) = mutateStatus { timelineRepository.setBookmarked(it, status.statusId, !status.bookmarked) }
    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus { timelineRepository.setFedibirdReaction(it, status.statusId, emoji) }
    fun votePoll(status: TimelineStatus, choices: Set<Int>) {
        val poll = status.poll ?: return
        mutateStatus("投票しました") { timelineRepository.votePoll(it, status.statusId, poll.id, choices) }
    }
    fun setPinned(status: TimelineStatus) = mutateStatus(
        if (status.pinned) "プロフィールの固定を解除しました" else "プロフィールに固定しました",
    ) { timelineRepository.setPinned(it, status.statusId, !status.pinned) }

    fun deleteStatus(status: TimelineStatus) {
        val snapshot = currentSnapshot() ?: return
        requestScope.launch {
            timelineRepository.deleteStatus(snapshot.account!!, status.statusId).forSession(snapshot).fold(
                { browsing.publish(snapshot, BrowsingSession.Change.StatusDeleted(status.statusId))
                    _uiState.update { it.copy(message = "投稿を削除しました") } }, ::showActionError,
            )
        }
    }
    fun followStatus(status: TimelineStatus) = moderationMenu.setFollowing(status.author.id, true)
    fun unfollowStatus(status: TimelineStatus) = moderationMenu.setFollowing(status.author.id, false)
    fun muteStatus(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.mute(status.author.id, enabled)
    fun blockStatus(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.block(status.author.id, enabled)
    fun reportStatus(status: TimelineStatus, comment: String) = accountRequest("報告を送信しました") {
        timelineRepository.reportStatus(it, status.author.id, status.statusId, comment)
    }
    fun loadLists() {
        val snapshot = currentSnapshot() ?: return
        if (_uiState.value.isLoadingLists) return
        _uiState.update { it.copy(isLoadingLists = true) }
        requestScope.launch {
            timelineRepository.getLists(snapshot.account!!).forSession(snapshot).fold(
                { lists -> _uiState.update { it.copy(lists = lists, isLoadingLists = false) } },
                { error -> _uiState.update { it.copy(isLoadingLists = false) }; showActionError(error) },
            )
        }
    }
    fun addToList(status: TimelineStatus, listId: String) = accountRequest("リストに追加しました") {
        timelineRepository.addAccountToList(it, listId, status.author.id)
    }
    fun addProfileToList(listId: String) {
        val target = _uiState.value.profile?.author?.id ?: return
        accountRequest("リストに追加しました") { timelineRepository.addAccountToList(it, listId, target) }
    }
    fun clearMessage(expected: String? = _uiState.value.message) = _uiState.update {
        if (it.message == expected) it.copy(message = null) else it
    }
    private fun showActionError(error: Throwable) {
        val message = if (error.requiresAuthentication) {
            "もう一度ログインして、必要な権限を許可してください。"
        } else error.message ?: "操作に失敗しました"
        _uiState.update { it.copy(message = message) }
    }
    private fun accountRequest(message: String, request: suspend (AccountSession) -> Result<Unit>) {
        val snapshot = currentSnapshot() ?: return
        requestScope.launch { request(snapshot.account!!).forSession(snapshot).fold(
            { _uiState.update { it.copy(message = message) } }, ::showActionError) }
    }
    private fun mutateStatus(successMessage: String? = null, request: suspend (AccountSession) -> Result<TimelineStatus>) {
        val snapshot = currentSnapshot() ?: return
        requestScope.launch { request(snapshot.account!!).forSession(snapshot).fold(
            { browsing.publish(snapshot, BrowsingSession.Change.StatusUpdated(it))
                _uiState.update { state -> state.copy(message = successMessage) } }, ::showActionError) }
    }
    private fun optimisticAction(status: TimelineStatus, begin: (AccountSession, TimelineStatus) -> PendingStatusAction?) {
        val snapshot = currentSnapshot() ?: return
        val pending = begin(snapshot.account!!, status) ?: return
        requestScope.launch { statusActionManager.complete(pending).forSession(snapshot).onFailure(::showActionError) }
    }
}
