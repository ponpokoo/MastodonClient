package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class TimelineUiState(
    val statuses: List<TimelineStatus> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val nextMaxId: String? = null,
    val errorMessage: String? = null,
    val announcements: List<ServerAnnouncement> = emptyList(),
    val announcementsVisible: Boolean = false,
    val isLoadingAnnouncements: Boolean = false,
    val announcementsError: String? = null,
    val refreshNewStatusCount: Int? = null,
    val selectedFeed: TimelineFeed = TimelineFeed.Home,
    val unseenStreamIds: Set<String> = emptySet(),
    val streamAutoScrollId: Long = 0,
    val streamAtTopCount: Int? = null,
    val resumeAnchorId: String? = null,
    val resumeOffset: Int = 0,
    val isResumedWindow: Boolean = false,
)

class TimelineViewModel(
    private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState = _uiState.asStateFlow()
    private var timelineJob: Job? = null
    private var followingTop = false
    private var streamSequence = 0L
    private var resumeCursor: String? = null

    class Factory(
        private val timelineRepository: TimelineRepository,
        private val browsing: BrowsingSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            TimelineViewModel(timelineRepository, browsing, extras.createSavedStateHandle()) as T
    }

    private data class SavedViewport(
        val feed: TimelineFeed,
        val anchorId: String,
        val beforeAnchorId: String?,
        val offset: Int,
    )
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        followingTop = false
        if (snapshot.account == null && snapshot.generation > 0) clearSavedViewport()
        val viewport = snapshot.account?.let { savedViewport(it.sessionId) }
        resumeCursor = viewport?.beforeAnchorId ?: viewport?.anchorId
        _uiState.value = TimelineUiState(
            isInitialLoading = snapshot.generation == 0L || snapshot.account != null,
            selectedFeed = viewport?.feed ?: TimelineFeed.Home,
        )
        if (snapshot.account != null) loadInitial(viewport)
    }

    fun refresh() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isRefreshing || _uiState.value.isInitialLoading || _uiState.value.isLoadingMore) return
        if (_uiState.value.isResumedWindow) {
            val viewport = savedViewport(session.sessionId) ?: run {
                goToLatest()
                return
            }
            timelineJob = requestScope.launch {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
                loadViewportPage(snapshot, _uiState.value.selectedFeed, viewport).onSuccess { page ->
                        val anchor = viewport.anchorId.takeIf { id -> page.statuses.any { it.timelineId == id } }
                            ?: page.statuses.firstOrNull()?.timelineId
                        resumeCursor = viewport.beforeAnchorId ?: viewport.anchorId
                        _uiState.update { it.copy(
                            statuses = page.statuses,
                            isRefreshing = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                            refreshNewStatusCount = null,
                            resumeAnchorId = anchor,
                            resumeOffset = if (anchor == viewport.anchorId) viewport.offset else 0,
                        ) }
                    }.onFailure { error -> showError(error, refreshing = true) }
            }
            return
        }
        val idsAtStart = _uiState.value.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
        timelineJob = requestScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            timelineRepository.getTimeline(session, _uiState.value.selectedFeed)
                .forSession(snapshot).onSuccess { page ->
                    val existingIds = _uiState.value.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
                    val newCount = page.statuses.count { it.timelineId !in existingIds }
                    _uiState.update {
                        it.copy(
                            statuses = (it.statuses.filter { status -> status.timelineId !in idsAtStart || status.timelineId in it.unseenStreamIds } + page.statuses)
                                .distinctBy(TimelineStatus::timelineId),
                            isRefreshing = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                            refreshNewStatusCount = newCount,
                        )
                    }
                }
                .onFailure { error -> showError(error, refreshing = true) }
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val cursor = state.nextMaxId ?: return
        if (state.isInitialLoading || state.isRefreshing || state.isLoadingMore || state.endReached) return
        timelineJob = requestScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            timelineRepository.getTimeline(session, state.selectedFeed, maxId = cursor)
                .forSession(snapshot).onSuccess { page ->
                    _uiState.update { current ->
                        current.copy(
                            statuses = (current.statuses + page.statuses)
                                .distinctBy(TimelineStatus::timelineId),
                            isLoadingMore = false,
                            endReached = page.endReached || page.nextMaxId == current.nextMaxId,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, loadingMore = true) }
        }
    }

    fun retry() {
        if (_uiState.value.statuses.isEmpty()) {
            val sessionId = currentSnapshot()?.account?.sessionId ?: return
            loadInitial(savedViewport(sessionId))
        } else loadNextPage()
    }

    fun selectFeed(feed: TimelineFeed) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (feed == _uiState.value.selectedFeed || _uiState.value.isInitialLoading) return
        timelineJob?.cancel()
        clearSavedViewport()
        resumeCursor = null
        timelineJob = requestScope.launch {
            _uiState.update {
                it.copy(
                    selectedFeed = feed,
                    unseenStreamIds = emptySet(),
                    streamAutoScrollId = 0,
                    streamAtTopCount = null,
                    statuses = emptyList(),
                    isInitialLoading = true,
                    isLoadingMore = false,
                    isRefreshing = false,
                    endReached = false,
                    nextMaxId = null,
                    errorMessage = null,
                    resumeAnchorId = null,
                    resumeOffset = 0,
                    isResumedWindow = false,
                )
            }
            timelineRepository.getTimeline(session, feed)
                .forSession(snapshot).onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            statuses = (it.statuses + page.statuses).distinctBy(TimelineStatus::timelineId),
                            isInitialLoading = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, initial = true) }
        }
    }

    fun showAnnouncements() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isLoadingAnnouncements) return
        requestScope.launch {
            _uiState.update {
                it.copy(
                    announcementsVisible = true,
                    isLoadingAnnouncements = true,
                    announcementsError = null,
                )
            }
            timelineRepository.getAnnouncements(session)
                .forSession(snapshot).onSuccess { announcements ->
                    _uiState.update {
                        it.copy(announcements = announcements, isLoadingAnnouncements = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingAnnouncements = false,
                            announcementsError = error.message ?: "お知らせを取得できませんでした",
                        )
                    }
                }
        }
    }

    fun dismissAnnouncements() {
        _uiState.update { it.copy(announcementsVisible = false) }
    }

    fun saveViewport(anchorId: String, beforeAnchorId: String?, offset: Int) {
        val session = currentSnapshot()?.account ?: return
        val state = _uiState.value
        if (state.isInitialLoading || state.isRefreshing || state.resumeAnchorId != null ||
            state.statuses.none { it.timelineId == anchorId }) return
        savedStateHandle[VIEWPORT_SESSION] = session.sessionId
        savedStateHandle[VIEWPORT_FEED] = state.selectedFeed.name
        savedStateHandle[VIEWPORT_ANCHOR] = anchorId
        val resolvedBeforeId = beforeAnchorId ?: if (state.isResumedWindow && resumeCursor != anchorId) resumeCursor else null
        savedStateHandle[VIEWPORT_BEFORE] = resolvedBeforeId.orEmpty()
        savedStateHandle[VIEWPORT_OFFSET] = offset.coerceAtLeast(0)
        if (state.isResumedWindow) resumeCursor = resolvedBeforeId ?: anchorId
    }

    fun clearSavedViewport() {
        savedStateHandle.remove<String>(VIEWPORT_SESSION)
        savedStateHandle.remove<String>(VIEWPORT_FEED)
        savedStateHandle.remove<String>(VIEWPORT_ANCHOR)
        savedStateHandle.remove<String>(VIEWPORT_BEFORE)
        savedStateHandle.remove<Int>(VIEWPORT_OFFSET)
    }

    fun consumeResumeAnchor() {
        _uiState.update { it.copy(resumeAnchorId = null, resumeOffset = 0) }
    }

    fun goToLatest() {
        val snapshot = currentSnapshot() ?: return
        val state = _uiState.value
        if (!state.isResumedWindow || state.isInitialLoading || state.isRefreshing) return
        timelineJob?.cancel()
        timelineJob = requestScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            timelineRepository.getTimeline(snapshot.account!!, state.selectedFeed)
                .forSession(snapshot).onSuccess { page ->
                    resumeCursor = null
                    clearSavedViewport()
                    _uiState.update { it.copy(
                        statuses = page.statuses,
                        isRefreshing = false,
                        endReached = page.endReached,
                        nextMaxId = page.nextMaxId,
                        isResumedWindow = false,
                        resumeAnchorId = page.statuses.firstOrNull()?.timelineId,
                        resumeOffset = 0,
                        unseenStreamIds = emptySet(),
                        refreshNewStatusCount = null,
                    ) }
                }.onFailure { error -> showError(error, refreshing = true) }
        }
    }

    fun updateViewport(atTopAndVisible: Boolean) {
        followingTop = atTopAndVisible && !_uiState.value.isResumedWindow
        if (followingTop) _uiState.update { it.copy(unseenStreamIds = emptySet()) }
    }

    fun consumeStreamNotice(id: Long) {
        _uiState.update { if (it.streamAutoScrollId == id) it.copy(streamAtTopCount = null) else it }
    }

    fun consumeRefreshResult() {
        _uiState.update { it.copy(refreshNewStatusCount = null) }
    }

    private fun loadInitial(viewport: SavedViewport? = null) {
        val snapshot = currentSnapshot() ?: return
        val feed = _uiState.value.selectedFeed
        timelineJob?.cancel()
        _uiState.update { it.copy(isInitialLoading = true, errorMessage = null) }
        timelineJob = requestScope.launch {
            val aroundAnchor = viewport != null
            var result = if (viewport != null) loadViewportPage(snapshot, feed, viewport)
                else timelineRepository.getTimeline(snapshot.account!!, feed).forSession(snapshot)
            var restored = viewport
            if (aroundAnchor && result.getOrNull()?.statuses?.isEmpty() == true) {
                result = timelineRepository.getTimeline(snapshot.account!!, feed).forSession(snapshot)
                restored = null
                resumeCursor = null
                clearSavedViewport()
            }
            result.fold(
                onSuccess = { page ->
                    val anchor = restored?.anchorId?.takeIf { id -> page.statuses.any { it.timelineId == id } }
                        ?: if (aroundAnchor && restored != null) page.statuses.firstOrNull()?.timelineId else null
                    _uiState.update { it.copy(
                        statuses = page.statuses,
                        isInitialLoading = false,
                        endReached = page.endReached,
                        nextMaxId = page.nextMaxId,
                        resumeAnchorId = anchor,
                        resumeOffset = if (anchor == restored?.anchorId) restored?.offset ?: 0 else 0,
                        isResumedWindow = aroundAnchor && restored != null,
                    ) }
                },
                onFailure = { showError(it, initial = true) },
            )
        }
    }

    private suspend fun loadViewportPage(
        snapshot: BrowsingSession.Snapshot,
        feed: TimelineFeed,
        viewport: SavedViewport,
    ): Result<TimelinePage> {
        val session = snapshot.account!!
        val cursor = viewport.beforeAnchorId ?: viewport.anchorId
        val page = timelineRepository.getTimeline(session, feed, maxId = cursor, limit = 40).forSession(snapshot)
        if (viewport.beforeAnchorId != null || page.isFailure) return page
        val anchor = timelineRepository.getTimelineStatus(session, viewport.anchorId).forSession(snapshot).getOrNull()
        return page.map { result ->
            if (anchor == null) result else result.copy(
                statuses = (listOf(anchor) + result.statuses).distinctBy(TimelineStatus::timelineId),
            )
        }
    }

    private fun savedViewport(sessionId: String): SavedViewport? {
        if (savedStateHandle.get<String>(VIEWPORT_SESSION) != sessionId) return null
        val feed = TimelineFeed.entries.firstOrNull {
            it.name == savedStateHandle.get<String>(VIEWPORT_FEED)
        } ?: return null
        val anchorId = savedStateHandle.get<String>(VIEWPORT_ANCHOR)?.takeIf(String::isNotBlank) ?: return null
        return SavedViewport(
            feed = feed,
            anchorId = anchorId,
            beforeAnchorId = savedStateHandle.get<String>(VIEWPORT_BEFORE)?.takeIf(String::isNotBlank),
            offset = savedStateHandle.get<Int>(VIEWPORT_OFFSET)?.coerceAtLeast(0) ?: 0,
        )
    }

    private companion object {
        const val VIEWPORT_SESSION = "timeline_viewport_session"
        const val VIEWPORT_FEED = "timeline_viewport_feed"
        const val VIEWPORT_ANCHOR = "timeline_viewport_anchor"
        const val VIEWPORT_BEFORE = "timeline_viewport_before"
        const val VIEWPORT_OFFSET = "timeline_viewport_offset"
    }

    override fun onChange(change: BrowsingSession.Change) {
        _uiState.update { current ->
            when (change) {
                is BrowsingSession.Change.StatusUpdated -> current.copy(statuses = current.statuses.map { it.withUpdatedActions(change.status) })
                is BrowsingSession.Change.StatusDeleted -> current.copy(statuses = current.statuses.filterNot { it.statusId == change.statusId }, unseenStreamIds = current.unseenStreamIds - current.statuses.filter { it.statusId == change.statusId }.map { it.timelineId }.toSet())
                is BrowsingSession.Change.Stream -> when (val event = change.event) {
                    is TimelineStreamEvent.StatusAdded -> if (current.selectedFeed == TimelineFeed.Home) {
                        val exists = current.statuses.any { it.timelineId == event.status.timelineId }
                        if (event.isEdit) {
                            current.copy(statuses = current.statuses.map {
                                if (it.statusId == event.status.statusId) event.status.copy(timelineId = it.timelineId, boostedBy = it.boostedBy) else it
                            })
                        } else if (exists) {
                            current.copy(statuses = current.statuses.map { if (it.timelineId == event.status.timelineId) event.status else it })
                        } else if (current.isResumedWindow) {
                            current.copy(unseenStreamIds = current.unseenStreamIds + event.status.timelineId)
                        } else {
                            current.copy(
                                statuses = listOf(event.status) + current.statuses,
                                unseenStreamIds = if (followingTop) emptySet() else current.unseenStreamIds + event.status.timelineId,
                                streamAutoScrollId = if (followingTop) ++streamSequence else current.streamAutoScrollId,
                                streamAtTopCount = if (followingTop) (current.streamAtTopCount ?: 0) + 1 else null,
                            )
                        }
                    } else current
                    is TimelineStreamEvent.StatusDeleted -> current.copy(statuses = current.statuses.filterNot { it.statusId == event.statusId || it.timelineId == event.statusId }, unseenStreamIds = current.unseenStreamIds - current.statuses.filter { it.statusId == event.statusId || it.timelineId == event.statusId }.map { it.timelineId }.toSet())
                    is TimelineStreamEvent.NotificationReceived -> current
                }
            }
        }
    }

    private fun showError(
        error: Throwable,
        initial: Boolean = false,
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
    ) {
        val message = when ((error as? HttpException)?.code()) {
            401 -> "ログインの有効期限が切れました。ログインし直してください。"
            429 -> "アクセスが集中しています。しばらく待ってから再試行してください。"
            else -> error.message ?: "ホームタイムラインを取得できませんでした。"
        }
        _uiState.update {
            it.copy(
                isInitialLoading = if (initial) false else it.isInitialLoading,
                isRefreshing = if (refreshing) false else it.isRefreshing,
                isLoadingMore = if (loadingMore) false else it.isLoadingMore,
                errorMessage = message,
            )
        }
    }

}
