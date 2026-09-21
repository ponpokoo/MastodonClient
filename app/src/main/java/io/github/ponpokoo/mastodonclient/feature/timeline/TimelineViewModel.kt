package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
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
)

class TimelineViewModel(private val timelineRepository: TimelineRepository, browsing: BrowsingSession) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState = _uiState.asStateFlow()
    private var timelineJob: Job? = null
    private var followingTop = false
    private var streamSequence = 0L
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        followingTop = false
        _uiState.value = TimelineUiState(isInitialLoading = snapshot.generation == 0L || snapshot.account != null)
        if (snapshot.account != null) loadInitial()
    }

    fun refresh() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isRefreshing || _uiState.value.isInitialLoading || _uiState.value.isLoadingMore) return
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
        if (_uiState.value.statuses.isEmpty()) loadInitial() else loadNextPage()
    }

    fun selectFeed(feed: TimelineFeed) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (feed == _uiState.value.selectedFeed || _uiState.value.isInitialLoading) return
        timelineJob?.cancel()
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

    fun updateViewport(atTopAndVisible: Boolean) {
        followingTop = atTopAndVisible
        if (atTopAndVisible) _uiState.update { it.copy(unseenStreamIds = emptySet()) }
    }

    fun consumeStreamNotice(id: Long) {
        _uiState.update { if (it.streamAutoScrollId == id) it.copy(streamAtTopCount = null) else it }
    }

    fun consumeRefreshResult() {
        _uiState.update { it.copy(refreshNewStatusCount = null) }
    }

    private fun loadInitial() {
        val snapshot = currentSnapshot() ?: return
        val feed = _uiState.value.selectedFeed
        timelineJob?.cancel()
        _uiState.update { it.copy(isInitialLoading = true, errorMessage = null) }
        timelineJob = requestScope.launch {
            timelineRepository.getTimeline(snapshot.account!!, feed).forSession(snapshot).fold(
                onSuccess = { page -> _uiState.update { it.copy(
                    statuses = (it.statuses + page.statuses).distinctBy(TimelineStatus::timelineId), isInitialLoading = false,
                    endReached = page.endReached, nextMaxId = page.nextMaxId,
                ) } },
                onFailure = { showError(it, initial = true) },
            )
        }
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
