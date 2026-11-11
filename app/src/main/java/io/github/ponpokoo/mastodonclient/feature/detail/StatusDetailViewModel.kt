package io.github.ponpokoo.mastodonclient.feature.detail

import io.github.ponpokoo.mastodonclient.domain.model.requiresAuthentication
import io.github.ponpokoo.mastodonclient.feature.common.AccountModerationMenu
import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.StatusUpdateKind
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.common.PendingStatusAction
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.common.withDisplayOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

data class StatusDetailUiState(
    val detail: StatusDetail? = null,
    val isLoading: Boolean = true,
    // Show an indicator only when the initial conversation needs a network fetch.
    val showInitialLoadingIndicator: Boolean = false,
    val errorMessage: String? = null,
    val currentAccountId: String? = null,
    val lists: List<MastodonList> = emptyList(),
    val isLoadingLists: Boolean = false,
    val actionMessage: String? = null,
    val isDeleted: Boolean = false,
    val votingPollIds: Set<String> = emptySet(),
    val sessionKey: String? = null,
)

data class StatusDetailSeed(val snapshot: BrowsingSession.Snapshot, val status: TimelineStatus)

class StatusDetailViewModel(
    private val statusId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
    private val browsing: BrowsingSession? = null,
    private val conversationSourceId: String? = null,
    private val seed: StatusDetailSeed? = null,
    private val actionOwner: StatusActionsViewModel? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatusDetailUiState())
    private var session: AccountSession? = null
    private val moderatedState = _uiState.moderated(viewModelScope, timelineRepository, { session }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    val uiState = if (browsing != null && actionOwner != null) {
        moderatedState.withDisplayOverlay(viewModelScope, browsing.optimisticActions, ::displayOptimisticActions)
    } else {
        moderatedState.withDisplayOverlay(viewModelScope, statusActionManager.optimisticActions) { state, updates ->
            val active = updates.filter { it.session.hasSameCredentials(session) }
                .associate { it.change.status.statusId to it.change }
            displayOptimisticActions(state, active)
        }
    }

    private fun displayOptimisticActions(
        state: StatusDetailUiState,
        actions: Map<String, BrowsingSession.Change.StatusUpdated>,
    ): StatusDetailUiState = if (actions.isEmpty()) state else state.copy(detail = state.detail?.mapStatuses {
        actions[it.statusId]?.applyTo(it) ?: it
    })
    private val actionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val actionScope = CoroutineScope(viewModelScope.coroutineContext + actionJob)
    private var loadJob: Job? = null
    private var updatesDuringLoad: MutableList<BrowsingSession.Change.StatusUpdated>? = null

    private val moderationMenu = AccountModerationMenu(timelineRepository, viewModelScope,
        session = { session }, context = { session }, isCurrent = { it.hasSameCredentials(authRepository.restoreSession()) },
        message = { message -> _uiState.update { it.copy(actionMessage = message) } })
    val moderationMenuState = moderationMenu.state
    fun loadModerationMenu(accountId: String?) = moderationMenu.load(accountId)
    init {
        browsing?.let { browsing ->
            viewModelScope.launch {
                var previous = browsing.snapshot.value
                browsing.snapshot.drop(1).collect { next ->
                    val firstActivation = previous.generation == 0L
                    previous = next
                    actionJob.cancelChildren()
                    session = null
                    _uiState.value = StatusDetailUiState()
                    load(showCachedDetail = firstActivation)
                }
            }
            viewModelScope.launch {
                browsing.events.collect { event ->
                    val change = event.change as? BrowsingSession.Change.StatusUpdated ?: return@collect
                    if (event.snapshot == browsing.snapshot.value && change.field == null &&
                        session?.hasSameCredentials(event.snapshot.account) == true) applyStatusChange(change)
                }
            }
        }
        io.github.ponpokoo.mastodonclient.feature.common.observeServerFilterChanges(viewModelScope, timelineRepository, { session }, "detail:$statusId") {
            _uiState.first { !it.isLoading }; load().join()
            _uiState.first { !it.isLoading }.errorMessage == null
        }
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val current = session ?: return@collect
                val snapshot = browsing?.snapshot?.value
                if (update.session.hasSameCredentials(current) &&
                    current.hasSameCredentials(authRepository.restoreSession()) &&
                    snapshot == browsing?.snapshot?.value &&
                    (browsing == null || current.hasSameCredentials(browsing.snapshot.value.account))) {
                    if (update.change.kind == StatusUpdateKind.Stored) applyStatusChange(update.change)
                }
            }
        }
        load(showCachedDetail = true)
    }

    fun retry() = load()
    fun refresh() {
        if (!_uiState.value.isLoading) load()
    }

    fun onReplyPosted(replyToId: String) {
        if (replyToId == statusId || findStatus(replyToId) != null) load()
    }

    fun toggleFavourite(status: TimelineStatus) = runOptimisticAction(status, statusActionManager::beginFavourite)

    fun toggleReblog(status: TimelineStatus) = runOptimisticAction(status, statusActionManager::beginReblog)

    fun toggleBookmark(status: TimelineStatus) = mutateStatus(
        status, update = { displayed, updated -> displayed.copy(bookmarked = updated.bookmarked) },
    ) { current, target -> timelineRepository.setBookmarked(current, target.statusId, !target.bookmarked) }

    fun setReaction(status: TimelineStatus, emoji: String?) = runOptimisticAction(status) { current, target ->
        statusActionManager.beginReaction(current, target, emoji)
    }

    fun votePoll(status: TimelineStatus, choices: Set<Int>) {
        val current = session ?: return
        val target = findStatus(status.statusId) ?: return
        val poll = target.poll ?: return
        if (poll.id in _uiState.value.votingPollIds || choices.isEmpty() || poll.expired || poll.voted == true) return
        val snapshot = browsing?.snapshot?.value
        _uiState.update { it.copy(votingPollIds = it.votingPollIds + poll.id) }
        actionScope.launch {
            try {
                if (!isCurrentAction(current, snapshot)) return@launch
                val result = timelineRepository.votePoll(current, target.statusId, poll.id, choices)
                if (!isCurrentAction(current, snapshot)) return@launch
                result.fold(
                    onSuccess = { updated ->
                        updateStatus(target.statusId) { it.copy(poll = updated.poll) }
                        _uiState.update { it.copy(actionMessage = "投票しました") }
                    },
                    onFailure = { showActionError(it, "投票できませんでした。もう一度お試しください") },
                )
            } finally {
                if (snapshot == browsing?.snapshot?.value) {
                    _uiState.update { it.copy(votingPollIds = it.votingPollIds - poll.id) }
                }
            }
        }
    }

    fun consumeActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    fun setPinned(status: TimelineStatus) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.setPinned(current, status.statusId, !status.pinned).fold(
                onSuccess = { updated -> _uiState.update { state -> state.copy(
                    detail = state.detail?.copy(status = updated),
                    actionMessage = if (updated.pinned) "プロフィールに固定しました" else "固定を解除しました",
                ) } },
                onFailure = { showActionError(it, "固定を変更できませんでした") },
            )
        }
    }

    fun deleteStatus(status: TimelineStatus) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.deleteStatus(current, status.statusId).fold(
                onSuccess = { _uiState.update { it.copy(isDeleted = true) } },
                onFailure = { showActionError(it, "投稿を削除できませんでした") },
            )
        }
    }

    fun follow(status: TimelineStatus) = moderationMenu.setFollowing(status.author.id, true)
    fun unfollow(status: TimelineStatus) = moderationMenu.setFollowing(status.author.id, false)

    fun mute(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.mute(status.author.id, enabled)
    fun block(status: TimelineStatus, enabled: Boolean = true) = moderationMenu.block(status.author.id, enabled)

    fun report(status: TimelineStatus, comment: String) = runAccountAction("報告を送信しました") {
        timelineRepository.reportStatus(it, status.author.id, status.statusId, comment)
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
                    showActionError(error, "リストを取得できませんでした")
                },
            )
        }
    }

    fun addToList(status: TimelineStatus, listId: String) = runAccountAction("リストに追加しました") {
        timelineRepository.addAccountToList(it, listId, status.author.id)
    }

    private fun runAccountAction(message: String, request: suspend (AccountSession) -> Result<*>) {
        val current = session ?: return
        viewModelScope.launch {
            request(current).fold(
                onSuccess = { _uiState.update { it.copy(actionMessage = message) } },
                onFailure = { showActionError(it, "操作に失敗しました") },
            )
        }
    }

    private fun showActionError(error: Throwable, fallback: String) {
        if (error is CancellationException) throw error
        _uiState.update { it.copy(actionMessage = if (error.requiresAuthentication) {
            "もう一度ログインして、必要な権限を許可してください。"
        } else error.message ?: fallback) }
    }

    private fun load(showCachedDetail: Boolean = false): Job {
        loadJob?.cancel()
        return actionScope.launch {
            val updates = mutableListOf<BrowsingSession.Change.StatusUpdated>()
            updatesDuringLoad = updates
            try {
                loadConversation(showCachedDetail, updates)
            } finally {
                if (updatesDuringLoad === updates) updatesDuringLoad = null
            }
        }.also { loadJob = it }
    }

    private suspend fun loadConversation(
        showCachedDetail: Boolean,
        updates: MutableList<BrowsingSession.Change.StatusUpdated>,
    ) {
        val snapshot = browsing?.snapshot?.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val current = authRepository.restoreSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (current == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "ログインし直してください") }
            return
        }
        if (!isCurrentAction(current, snapshot)) return
        session = current
        _uiState.update { it.copy(currentAccountId = current.accountId,
            sessionKey = "${current.sessionId}|${current.instanceUrl}|${snapshot?.generation}") }
        if (showCachedDetail) {
            val tapped = seed?.takeIf { it.snapshot == snapshot && it.status.statusId == statusId }?.status
            val conversation = timelineRepository.getCachedConversation(current, conversationSourceId ?: statusId, statusId)
            val cachedStatus = tapped ?: conversation?.status ?: timelineRepository.getCachedStatus(current, statusId)
            val initial = cachedStatus ?: timelineRepository.getCachedHomeStatus(current, statusId)
            if (cachedStatus == null && !isCurrentAction(current, snapshot)) return
            if (initial != null) {
                val selected = initial.copy(timelineId = statusId, boostedBy = null)
                val cached = conversation?.copy(status = selected) ?: StatusDetail(selected, emptyList(), emptyList())
                _uiState.update { state ->
                    state.copy(detail = cached.mapStatuses { status ->
                        updates.fold(status) { post, change -> change.applyTo(post) }
                    })
                }
            }
        }
        _uiState.update { it.copy(showInitialLoadingIndicator = it.detail == null) }
        updates.clear()
        val result = timelineRepository.getStatusDetail(current, statusId)
        if (!isCurrentAction(current, snapshot)) return
        result.onSuccess { detail ->
            val confirmed = detail.mapStatuses { status -> updates.fold(status) { post, change -> change.applyTo(post) } }
            _uiState.update { state ->
                state.copy(detail = confirmed, isLoading = false)
            }
            if (snapshot != null) {
                (listOf(confirmed.status) + confirmed.ancestors + confirmed.descendants).distinctBy { it.statusId }.forEach { status ->
                    browsing.publish(snapshot, BrowsingSession.Change.StatusUpdated(status, kind = StatusUpdateKind.Stored))
                }
            }
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "投稿を取得できませんでした",
                        actionMessage = if (it.detail != null) "会話を更新できませんでした" else it.actionMessage,
                    )
                }
            }
    }

    private fun applyStatusChange(change: BrowsingSession.Change.StatusUpdated) {
        if (change.kind == StatusUpdateKind.Stored || change.kind == StatusUpdateKind.Confirmed) {
            updatesDuringLoad?.add(change)
        }
        _uiState.update { state -> state.copy(detail = state.detail?.mapStatuses(change::applyTo)) }
    }

    private fun findStatus(id: String): TimelineStatus? {
        val detail = _uiState.value.detail ?: return null
        return sequenceOf(detail.status).plus(detail.ancestors).plus(detail.descendants).firstOrNull { it.statusId == id }
    }

    private fun updateStatus(id: String, transform: (TimelineStatus) -> TimelineStatus) {
        _uiState.update { state -> state.copy(
            detail = state.detail?.mapStatuses { if (it.statusId == id) transform(it) else it },
        ) }
    }

    private suspend fun isCurrentAction(current: AccountSession, snapshot: BrowsingSession.Snapshot?): Boolean {
        val restored = authRepository.restoreSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return snapshot == browsing?.snapshot?.value &&
            (browsing == null || current.hasSameCredentials(snapshot?.account)) &&
            current.hasSameCredentials(restored)
    }

    private fun mutateStatus(
        status: TimelineStatus,
        update: (TimelineStatus, TimelineStatus) -> TimelineStatus,
        request: suspend (AccountSession, TimelineStatus) -> Result<TimelineStatus>,
    ) {
        val current = session ?: return
        val snapshot = browsing?.snapshot?.value
        actionScope.launch {
            if (!isCurrentAction(current, snapshot)) return@launch
            val target = findStatus(status.statusId) ?: return@launch
            val result = request(current, target)
            if (!isCurrentAction(current, snapshot)) return@launch
            result.onSuccess { updated ->
                updateStatus(target.statusId) { update(it, updated) }
            }.onFailure { showActionError(it, "投稿を更新できませんでした") }
        }
    }

    private fun runOptimisticAction(
        status: TimelineStatus,
        begin: (AccountSession, TimelineStatus) -> PendingStatusAction?,
    ) {
        val current = session ?: return
        val snapshot = browsing?.snapshot?.value
        val block: suspend CoroutineScope.() -> Unit = action@{
            if (!isCurrentAction(current, snapshot)) return@action
            val target = findStatus(status.statusId) ?: return@action
            val pending = begin(current, target) ?: return@action
            val result = statusActionManager.complete(pending) {
                snapshot == browsing?.snapshot?.value
            }
            if (!isCurrentAction(current, snapshot)) return@action
            result.onFailure { showActionError(it, "投稿を更新できませんでした") }
        }
        if (actionOwner != null && snapshot != null) actionOwner.launchStatusAction(snapshot, block)
        else actionScope.launch(block = block)
    }

    class Factory(
        private val statusId: String,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
        private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
        private val browsing: BrowsingSession? = null,
        private val conversationSourceId: String? = null,
        private val seed: StatusDetailSeed? = null,
        private val actionOwner: StatusActionsViewModel? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StatusDetailViewModel(statusId, timelineRepository, authRepository, statusActionManager, browsing, conversationSourceId, seed, actionOwner) as T
    }
}

private fun StatusDetail.mapStatuses(transform: (TimelineStatus) -> TimelineStatus) = copy(
    status = transform(status),
    ancestors = ancestors.map(transform),
    descendants = descendants.map(transform),
)
