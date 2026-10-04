package io.github.ponpokoo.mastodonclient.feature.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.*
import io.github.ponpokoo.mastodonclient.feature.search.exploreMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HashtagTimelineUiState(
    val statuses: List<TimelineStatus> = emptyList(), val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false, val nextMaxId: String? = null, val endReached: Boolean = false,
    val errorMessage: String? = null, val actionMessage: String? = null,
    val tag: SearchTag? = null, val isChangingSubscription: Boolean = false,
    val subscriptionError: String? = null, val sessionKey: String? = null,
)

class HashtagTimelineViewModel(private val hashtag: String, private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession, private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
) : SessionScopedViewModel(browsing) {
    private val mutableState = MutableStateFlow(HashtagTimelineUiState())
    val uiState = mutableState.asStateFlow()
    private var timelineJob: Job? = null
    private var timelineGeneration = 0L
    private var tagJob: Job? = null
    private var tagGeneration = 0L

    init {
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val current = currentSnapshot()?.account ?: return@collect
                if (update.sessionId == current.sessionId && update.instanceUrl == current.instanceUrl) mutableState.update {
                    it.copy(statuses = it.statuses.map { status -> status.withStatusActionUpdate(update) })
                }
            }
        }
        observeSession()
    }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        timelineGeneration++
        tagGeneration++
        timelineJob = null
        tagJob = null
        mutableState.value = HashtagTimelineUiState(isLoading = snapshot.account != null,
            sessionKey = snapshot.account?.let { "${it.sessionId}:${snapshot.generation}" })
        if (snapshot.account != null) { refresh(); loadTag() }
    }

    private fun loadTag() {
        val snapshot = currentSnapshot() ?: return
        val generation = ++tagGeneration
        tagJob?.cancel()
        mutableState.update { it.copy(subscriptionError = null) }
        tagJob = requestScope.launch {
            val response = timelineRepository.getTag(snapshot.account!!, hashtag).forSession(snapshot)
            if (tagGeneration != generation) return@launch
            response.fold(onSuccess = { tag -> mutableState.update { it.copy(tag = tag, subscriptionError = null) } },
                onFailure = { error -> mutableState.update { it.copy(subscriptionError = error.exploreMessage()) } })
        }
    }

    fun retrySubscription() = loadTag()

    fun refresh() = requestTimeline(false)

    fun loadMore() {
        val state = uiState.value
        if (!state.isLoading && !state.isLoadingMore && !state.endReached && state.nextMaxId != null) requestTimeline(true)
    }

    private fun requestTimeline(append: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val cursor = if (append) uiState.value.nextMaxId ?: return else null
        timelineJob?.cancel()
        val generation = ++timelineGeneration
        mutableState.update { it.copy(isLoading = !append, isLoadingMore = append, errorMessage = null) }
        timelineJob = requestScope.launch {
            val response = timelineRepository.getHashtagTimeline(snapshot.account!!, hashtag, cursor, 20).forSession(snapshot)
            if (generation != timelineGeneration) return@launch
            response.fold(onSuccess = { page -> mutableState.update { state -> state.copy(
                statuses = if (append) (state.statuses + page.statuses).distinctBy { it.timelineId } else page.statuses,
                isLoading = false, isLoadingMore = false, nextMaxId = page.nextMaxId,
                endReached = page.endReached || (append && page.nextMaxId == cursor),
            ) } }, onFailure = { mutableState.update { it.copy(isLoading = false, isLoadingMore = false,
                errorMessage = "投稿を取得できませんでした。もう一度お試しください。") } })
        }
    }

    fun toggleSubscription() {
        if (uiState.value.isChangingSubscription) return
        val snapshot = currentSnapshot() ?: return
        val following = uiState.value.tag?.following ?: run { loadTag(); return }
        tagGeneration++
        tagJob?.cancel()
        mutableState.update { it.copy(isChangingSubscription = true, subscriptionError = null) }
        tagJob = requestScope.launch {
            timelineRepository.setTagFollowing(snapshot.account!!, hashtag, !following).forSession(snapshot).fold(
                onSuccess = { tag ->
                    mutableState.update { it.copy(tag = tag, isChangingSubscription = false) }
                    browsing.publish(snapshot, BrowsingSession.Change.TagUpdated(tag))
                }, onFailure = { error -> mutableState.update { it.copy(isChangingSubscription = false,
                    actionMessage = error.exploreMessage()) } },
            )
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (change is BrowsingSession.Change.TagUpdated && change.tag.name.equals(hashtag, ignoreCase = true)) {
            tagGeneration++
            mutableState.update { it.copy(tag = change.tag, subscriptionError = null) }
        }
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        mutableState.update { it.copy(statuses = it.statuses.filterNot { status -> status.statusId == deleted }.map { status ->
            if (change is BrowsingSession.Change.StatusUpdated) status.withUpdatedActions(change.status) else status
        }) }
    }

    fun toggleFavourite(status: TimelineStatus) = runOptimisticAction(status, statusActionManager::beginFavourite)
    fun toggleReblog(status: TimelineStatus) = runOptimisticAction(status, statusActionManager::beginReblog)
    fun setReaction(status: TimelineStatus, emoji: String?) {
        val snapshot = currentSnapshot() ?: return
        requestScope.launch {
            timelineRepository.setFedibirdReaction(snapshot.account!!, status.statusId, emoji).forSession(snapshot).onSuccess { updated ->
                mutableState.update { state -> state.copy(statuses = state.statuses.map { if (it.statusId == status.statusId) updated else it }) }
                browsing.publish(snapshot, BrowsingSession.Change.StatusUpdated(updated))
            }
        }
    }

    fun consumeActionMessage() { mutableState.update { it.copy(actionMessage = null) } }

    private fun runOptimisticAction(status: TimelineStatus, begin: (AccountSession, TimelineStatus) -> PendingStatusAction?) {
        val snapshot = currentSnapshot() ?: return
        val pending = begin(snapshot.account!!, status) ?: return
        requestScope.launch {
            statusActionManager.complete(pending).forSession(snapshot).onFailure { mutableState.update {
                it.copy(actionMessage = "投稿を更新できませんでした")
            } }
        }
    }

    class Factory(private val hashtag: String, private val timelineRepository: TimelineRepository,
        private val browsing: BrowsingSession, private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HashtagTimelineViewModel(hashtag, timelineRepository, browsing, statusActionManager) as T
    }
}
