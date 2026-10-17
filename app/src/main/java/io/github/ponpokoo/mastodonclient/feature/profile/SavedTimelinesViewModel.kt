package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedTimelinesUiState(
    val lists: List<MastodonList> = emptyList(),
    val statuses: List<TimelineStatus> = emptyList(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val error: String? = null,
    val appendFailed: Boolean = false,
    val sessionKey: String? = null,
)

class SavedTimelinesViewModel(
    private val kind: SavedTimelineKind?,
    private val listId: String?,
    private val repository: TimelineRepository,
    browsing: BrowsingSession,
) : SessionScopedViewModel(browsing) {
    private val mutableState = MutableStateFlow(SavedTimelinesUiState())
    val state = mutableState.moderated(viewModelScope, repository, { currentSnapshot()?.account }) { state, moderation, account ->
        state.withModeration(moderation, account, if (kind == SavedTimelineKind.List) "home" else null)
    }
    private var pageJob: Job? = null
    private var generation = 0L
    private val updatesDuringRequest = mutableMapOf<String, TimelineStatus>()
    private val deletedDuringRequest = mutableSetOf<String>()

    init {
        observeSession()
    }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        generation++
        pageJob = null
        updatesDuringRequest.clear()
        deletedDuringRequest.clear()
        mutableState.value = SavedTimelinesUiState(
            loading = snapshot.account != null,
            sessionKey = snapshot.account?.let { "${it.sessionId}:${snapshot.generation}" },
            error = if (snapshot.account == null) "ログインが必要です" else null,
        )
        if (snapshot.account != null) {
            requestPage(append = false, pull = false)
            if (kind == SavedTimelineKind.List) observeServerFilterChanges(requestScope, repository,
                { currentSnapshot()?.account }, "list:$listId") {
                requestPage(append = false, pull = false)?.join()
                Result.success(Unit).forSession(snapshot)
                !mutableState.value.loading && mutableState.value.error == null
            }
        }
    }

    fun refresh() { requestPage(append = false, pull = true) }
    fun retry() { requestPage(append = mutableState.value.appendFailed, pull = false) }

    fun loadMore() {
        val state = mutableState.value
        if (kind != null && !state.loading && !state.loadingMore && state.error == null &&
            !state.endReached && state.nextMaxId != null) requestPage(append = true, pull = false)
    }

    private fun requestPage(append: Boolean, pull: Boolean): Job? {
        val snapshot = currentSnapshot() ?: return null
        val cursor = if (append) mutableState.value.nextMaxId ?: return null else null
        pageJob?.cancel()
        val requestGeneration = ++generation
        updatesDuringRequest.clear()
        deletedDuringRequest.clear()
        mutableState.update { it.copy(loading = !append, refreshing = !append && pull && it.loaded,
            loadingMore = append, error = null, appendFailed = false) }
        return requestScope.launch {
            if (kind == null) {
                val response = repository.getLists(snapshot.account!!).forSession(snapshot)
                if (requestGeneration != generation) return@launch
                response.fold(
                    onSuccess = { lists -> mutableState.update { it.copy(lists = lists, loading = false,
                        loaded = true, refreshing = false) } },
                    onFailure = { fail(it, append = false) },
                )
            } else {
                val response = repository.getSavedTimeline(snapshot.account!!, kind, listId, cursor).forSession(snapshot)
                if (requestGeneration != generation) return@launch
                response.fold(onSuccess = { page ->
                    val statuses = page.statuses.filterNot { it.statusId in deletedDuringRequest }.map { status ->
                        updatesDuringRequest[status.statusId]?.let { status.mergeActions(it) } ?: status
                    }
                    mutableState.update { it.copy(
                        statuses = (if (append) it.statuses + statuses else statuses).distinctBy(TimelineStatus::timelineId),
                        nextMaxId = page.nextMaxId, endReached = page.endReached || (append && page.nextMaxId == cursor),
                        loading = false, loaded = true, refreshing = false, loadingMore = false,
                    ) }
                }, onFailure = { fail(it, append) })
            }
        }.also { pageJob = it }
    }

    private fun fail(error: Throwable, append: Boolean) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(loading = false, refreshing = false, loadingMore = false,
            appendFailed = append, error = error.message ?: if (kind == null) "リストを取得できませんでした" else "投稿を取得できませんでした") }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (kind == null) return
        val requesting = mutableState.value.loading || mutableState.value.loadingMore
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        if (deleted != null) {
            if (requesting) deletedDuringRequest.add(deleted)
            mutableState.update { it.copy(statuses = it.statuses.filterNot { status -> status.statusId == deleted }) }
        }
        if (change is BrowsingSession.Change.StatusUpdated) {
            if (requesting) updatesDuringRequest[change.status.statusId] = change.status
            mutableState.update { it.copy(statuses = it.statuses.map { status -> status.mergeActions(change.status) }) }
        }
    }

    private fun TimelineStatus.mergeActions(updated: TimelineStatus): TimelineStatus =
        if (statusId != updated.statusId) this else withUpdatedActions(updated).copy(poll = updated.poll)
}
