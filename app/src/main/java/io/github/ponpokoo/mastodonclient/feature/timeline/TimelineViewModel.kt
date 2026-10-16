package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.RequestFailure
import io.github.ponpokoo.mastodonclient.domain.model.requestFailure
import io.github.ponpokoo.mastodonclient.domain.model.requiresAuthentication
import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
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
import io.github.ponpokoo.mastodonclient.domain.session.applyHomeChange
import io.github.ponpokoo.mastodonclient.domain.session.homeIndex
import io.github.ponpokoo.mastodonclient.domain.session.mergeHomePage
import io.github.ponpokoo.mastodonclient.domain.session.fillHomeGap
import io.github.ponpokoo.mastodonclient.domain.session.applyHomeWindowChange
import io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineIndex
import io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineGap
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val isHomeSyncing: Boolean = false,
    val isShowingSavedStatuses: Boolean = false,
    val homeIndex: HomeTimelineIndex? = null,
    val loadingGapId: String? = null,
    val automaticGapCheck: HomeTimelineGap? = null,
    val gapErrors: Map<String, String> = emptyMap(),
)

class TimelineViewModel(
    private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private var timelineJob: Job? = null
    private var streamGapProbeJob: Job? = null
    private var followingTop = false
    private var streamSequence = 0L
    private var resumeCursor: String? = null
    private var homeChangesDuringRequest: MutableList<BrowsingSession.Change>? = null
    private var blockedAutomaticCursor: String? = null
    private var keepPositionPreference: Boolean? = null
    private val keepsPosition get() = keepPositionPreference ?:
        (_uiState.value.isResumedWindow || currentSnapshot()?.account?.let { savedViewport(it.sessionId) } != null)
    private val keepsHomePosition get() = _uiState.value.selectedFeed == TimelineFeed.Home && keepsPosition

    fun setKeepPositionOnRefresh(enabled: Boolean) {
        val wasKeeping = keepsPosition
        keepPositionPreference = enabled
        val state = _uiState.value
        val index = state.homeIndex ?: return
        if (enabled || !wasKeeping) return
        streamGapProbeJob?.cancel()
        // Leaving window mode must not render disconnected rows without their boundaries.
        timelineJob?.cancel()
        homeChangesDuringRequest = null
        val gap = index.gaps.minByOrNull { index.ids.indexOf(it.beforeId) }
        val ids = if (gap == null) index.ids else index.ids.takeWhile { it != gap.beforeId }
        val statuses = state.statuses.filter { it.timelineId in ids }
        _uiState.update { it.copy(statuses = statuses, homeIndex = null,
            nextMaxId = gap?.maxId ?: state.nextMaxId, endReached = gap == null && state.endReached,
            isRefreshing = false, isLoadingMore = false, isHomeSyncing = false, loadingGapId = null, automaticGapCheck = null, gapErrors = emptyMap(),
            resumeAnchorId = if (gap != null) statuses.firstOrNull()?.timelineId else it.resumeAnchorId,
            resumeOffset = if (gap != null) 0 else it.resumeOffset) }
    }

    private fun homeWindow() = _uiState.value.let { TimelinePage(it.statuses, it.nextMaxId, it.endReached, it.homeIndex) }

    fun loadGap(beforeId: String) = loadGap(beforeId, automatic = false)

    private fun loadGap(beforeId: String, automatic: Boolean) {
        val state = _uiState.value
        val snapshot = currentSnapshot() ?: return
        val gap = state.homeIndex?.gaps?.firstOrNull { it.beforeId == beforeId && !it.unavailable } ?: return
        if (!keepsPosition || state.isInitialLoading || state.isRefreshing || state.isLoadingMore || state.isHomeSyncing || state.loadingGapId != null) return
        if (automatic && !timelineRepository.isNetworkAvailable()) return
        _uiState.update { it.copy(loadingGapId = beforeId, gapErrors = it.gapErrors - beforeId) }
        timelineJob = requestScope.launch {
            requestTimeline(snapshot, state.selectedFeed, maxId = gap.maxId, gap = gap)
                .forSession(snapshot).onSuccess { page ->
                    _uiState.update { it.copy(statuses = page.statuses, homeIndex = page.homeIndex,
                        nextMaxId = page.nextMaxId, endReached = page.endReached, loadingGapId = null) }
                }.onFailure { error ->
                    _uiState.update { it.copy(loadingGapId = null, gapErrors = it.gapErrors +
                        (beforeId to (error.message ?: "読み込めませんでした。再試行してください。"))) }
                }
        }
    }

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
        streamGapProbeJob?.cancel()
        blockedAutomaticCursor = null
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
        if (_uiState.value.isRefreshing || _uiState.value.isInitialLoading || _uiState.value.isLoadingMore || _uiState.value.isHomeSyncing || _uiState.value.loadingGapId != null) return
        if (_uiState.value.isResumedWindow && !keepsPosition) {
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
            requestTimeline(snapshot, _uiState.value.selectedFeed)
                .forSession(snapshot).onSuccess { page ->
                    val existingIds = _uiState.value.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
                    val pageIds = page.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
                    val newCount = page.statuses.count {
                        it.timelineId !in existingIds && !it.isActivityBy(session.accountId)
                    }
                    if (keepsPosition) {
                        _uiState.update { it.copy(statuses = page.statuses, homeIndex = page.homeIndex,
                            isRefreshing = false, endReached = page.endReached, nextMaxId = page.nextMaxId,
                            isResumedWindow = false, refreshNewStatusCount = newCount) }
                        return@onSuccess
                    }
                    _uiState.update {
                        it.copy(
                            // Overlapping rows take their position from the response, even when unread.
                            statuses = (it.statuses.filter { status ->
                                status.timelineId !in pageIds &&
                                    (status.timelineId !in idsAtStart || status.timelineId in it.unseenStreamIds)
                            } + page.statuses)
                                .distinctBy(TimelineStatus::timelineId),
                            homeIndex = null,
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
        loadNextPage(manual = false)
    }

    private fun loadNextPage(manual: Boolean) {
        val state = _uiState.value
        val snapshot = currentSnapshot() ?: return
        val cursor = state.nextMaxId ?: return
        if (!manual && state.selectedFeed == TimelineFeed.Home && cursor == blockedAutomaticCursor) return
        if (state.isInitialLoading || state.isRefreshing || state.isLoadingMore || state.isHomeSyncing || state.loadingGapId != null || state.endReached) return
        timelineJob = requestScope.launch {
            var provisionalIds = emptySet<String>()
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            requestTimeline(snapshot, state.selectedFeed, maxId = cursor, automatic = !manual, onCached = { page ->
                provisionalIds = page.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
                _uiState.update { current -> current.copy(
                    statuses = (current.statuses + page.statuses).distinctBy(TimelineStatus::timelineId),
                ) }
            })
                .forSession(snapshot).onSuccess { page ->
                    _uiState.update { current ->
                        current.copy(
                            statuses = if (keepsPosition) page.statuses else
                                (current.statuses.filterNot { it.timelineId in provisionalIds } + page.statuses).distinctBy(TimelineStatus::timelineId),
                            homeIndex = if (keepsPosition) page.homeIndex else current.homeIndex,
                            isLoadingMore = false,
                            endReached = page.endReached || page.nextMaxId == current.nextMaxId,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error ->
                    if (!manual && state.selectedFeed == TimelineFeed.Home) blockedAutomaticCursor = cursor
                    showError(error, loadingMore = true, automatic = !manual)
                }
        }
    }

    fun retry() {
        if (_uiState.value.statuses.isEmpty() && _uiState.value.homeIndex?.ids.isNullOrEmpty()) {
            val sessionId = currentSnapshot()?.account?.sessionId ?: return
            loadInitial(savedViewport(sessionId))
        } else loadNextPage(manual = true)
    }

    fun selectFeed(feed: TimelineFeed) {
        currentSnapshot() ?: return
        if (feed == _uiState.value.selectedFeed || _uiState.value.isInitialLoading) return
        streamGapProbeJob?.cancel()
        timelineJob?.cancel()
        blockedAutomaticCursor = null
        clearSavedViewport()
        resumeCursor = null
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
                isHomeSyncing = false,
                isShowingSavedStatuses = false,
                homeIndex = null,
                loadingGapId = null,
                automaticGapCheck = null,
                gapErrors = emptyMap(),
            )
        }
        loadInitial()
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
        if (keepsHomePosition && savedStateHandle.get<String>(VIEWPORT_ANCHOR) != anchorId) {
            val snapshot = currentSnapshot() ?: return
            requestScope.launch { timelineRepository.protectHomeViewport(session, anchorId).forSession(snapshot) }
        }
        savedStateHandle[VIEWPORT_SESSION] = session.sessionId
        savedStateHandle[VIEWPORT_FEED] = state.selectedFeed.name
        savedStateHandle[VIEWPORT_ANCHOR] = anchorId
        val index = state.homeIndex
        val anchorPosition = index?.ids?.indexOf(anchorId) ?: -1
        val gapBefore = index?.gaps?.any { it.beforeId == anchorId } == true
        val resolvedBeforeId = if (gapBefore) null else if (anchorPosition > 0) index?.ids?.get(anchorPosition - 1)
            else beforeAnchorId ?: if (state.isResumedWindow && resumeCursor != anchorId) resumeCursor else null
        savedStateHandle[VIEWPORT_BEFORE] = resolvedBeforeId.orEmpty()
        savedStateHandle[VIEWPORT_OFFSET] = offset
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
        if (!state.isResumedWindow || state.isInitialLoading || state.isRefreshing || state.isHomeSyncing) return
        timelineJob?.cancel()
        timelineJob = requestScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            requestTimeline(snapshot, state.selectedFeed)
                .forSession(snapshot).onSuccess { page ->
                    resumeCursor = null
                    clearSavedViewport()
                    _uiState.update { it.copy(
                        statuses = page.statuses,
                        homeIndex = if (keepsPosition) page.homeIndex else null,
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
        _uiState.update {
            if (it.streamAutoScrollId == id) it.copy(streamAutoScrollId = 0, streamAtTopCount = null) else it
        }
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
            var displayedCache = false
            fun displayCached(page: TimelinePage) {
                displayedCache = true
                _uiState.update { it.copy(
                    statuses = page.statuses,
                    homeIndex = if (keepsPosition) page.homeIndex else null,
                    isInitialLoading = false,
                    endReached = page.endReached,
                    nextMaxId = page.nextMaxId,
                    resumeAnchorId = viewport?.anchorId?.takeIf { id -> page.statuses.any { it.timelineId == id } },
                    resumeOffset = viewport?.offset ?: 0,
                    isResumedWindow = viewport != null,
                ) }
            }
            var result = if (viewport != null) loadViewportPage(snapshot, feed, viewport, automatic = true, onCached = ::displayCached)
                else requestTimeline(snapshot, feed, automatic = true, onCached = ::displayCached)
            var restored = viewport
            if (aroundAnchor && result.isFailure && feed == TimelineFeed.Home) {
                val fallback = timelineRepository.getCachedHomeTimeline(snapshot.account!!).forSession(snapshot).getOrNull()
                if (fallback != null && fallback.statuses.isNotEmpty()) {
                    result = Result.success(fallback)
                    restored = null
                    resumeCursor = null
                    clearSavedViewport()
                    _uiState.update { it.copy(isShowingSavedStatuses = true) }
                }
            }
            if (aroundAnchor && result.getOrNull()?.statuses?.isEmpty() == true) {
                result = requestTimeline(snapshot, feed, automatic = true, onCached = ::displayCached)
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
                        homeIndex = if (keepsPosition) page.homeIndex else null,
                        isInitialLoading = false,
                        endReached = page.endReached,
                        nextMaxId = page.nextMaxId,
                        // LazyColumn keeps stable keys while revalidating cached rows. Do not
                        // scroll back to the startup anchor after the reader has moved.
                        resumeAnchorId = if (displayedCache) it.resumeAnchorId?.takeIf { id -> page.statuses.any { row -> row.timelineId == id } } else anchor,
                        resumeOffset = if (displayedCache) it.resumeOffset else if (anchor == restored?.anchorId) restored?.offset ?: 0 else 0,
                        isResumedWindow = aroundAnchor && restored != null,
                    ) }
                },
                onFailure = { showError(it, initial = true, automatic = true) },
            )
        }
    }

    private suspend fun loadViewportPage(
        snapshot: BrowsingSession.Snapshot,
        feed: TimelineFeed,
        viewport: SavedViewport,
        automatic: Boolean = false,
        onCached: (TimelinePage) -> Unit = {},
    ): Result<TimelinePage> {
        val session = snapshot.account!!
        val cursor = viewport.beforeAnchorId ?: viewport.anchorId
        val page = requestTimeline(snapshot, feed, maxId = cursor, limit = if (feed == TimelineFeed.Home) 20 else 40,
            automatic = automatic, anchorId = viewport.anchorId, onCached = onCached)
        if (viewport.beforeAnchorId != null || page.isFailure) return page
        if (page.getOrNull()?.statuses?.any { it.timelineId == viewport.anchorId } == true) return page
        val anchor = timelineRepository.getTimelineStatus(session, viewport.anchorId).forSession(snapshot).getOrNull()
        val restored = page.map { result ->
            if (anchor == null) result else result.copy(
                statuses = (listOf(anchor) + result.statuses).distinctBy(TimelineStatus::timelineId),
                homeIndex = if (keepsPosition) result.homeIndex().copy(ids =
                    (listOf(anchor.timelineId) + result.homeIndex().ids).distinct()) else result.homeIndex,
            )
        }
        if (keepsHomePosition && anchor != null) restored.getOrNull()?.let {
            timelineRepository.saveHomeWindow(session, it, viewport.anchorId).forSession(snapshot)
        }
        return restored
    }

    private suspend fun requestTimeline(
        snapshot: BrowsingSession.Snapshot,
        feed: TimelineFeed,
        maxId: String? = null,
        limit: Int = 20,
        automatic: Boolean = false,
        anchorId: String? = null,
        onCached: (TimelinePage) -> Unit = {},
        gap: HomeTimelineGap? = null,
    ): Result<TimelinePage> {
        val session = snapshot.account!!
        suspend fun probe(previous: TimelinePage, page: TimelinePage, merged: TimelinePage): TimelinePage {
            val ids = page.homeIndex().ids
            if (maxId != null || gap != null || ids.size !in 1 until limit ||
                ids.any { it in previous.homeIndex().ids }) return merged
            val boundary = merged.homeIndex().gaps.firstOrNull {
                it.beforeId == previous.homeIndex().ids.firstOrNull() &&
                    it.maxId == page.nextMaxId && !it.unavailable
            } ?: return merged
            // One extra page only. A failure keeps the successful refresh and its retryable gap.
            val extra = requestGapPage(snapshot, feed, boundary, limit)
                .forSession(snapshot).getOrNull() ?: return merged
            return merged.fillHomeGap(boundary, extra)
        }
        if (feed != TimelineFeed.Home) {
            return (if (gap != null) requestGapPage(snapshot, feed, gap, limit)
                else timelineRepository.getTimeline(session, feed, maxId, limit)).forSession(snapshot).map { page ->
                // Public feeds share range merging and gaps, but never write the Home cache.
                if (!keepsPosition) page
                else if (_uiState.value.isInitialLoading) page.copy(homeIndex = page.homeIndex())
                else if (gap != null) homeWindow().fillHomeGap(gap, page)
                else homeWindow().let { previous -> probe(previous, page, previous.mergeHomePage(page, maxId)) }
            }
        }
        val changes = mutableListOf<BrowsingSession.Change>()
        fun applyPending(page: TimelinePage): TimelinePage = if (keepsHomePosition)
            changes.fold(page) { window, change -> window.applyHomeWindowChange(change) }
        else page.copy(statuses = changes.fold(page.statuses) { rows, change -> rows.applyHomeChange(change, includeNew = maxId == null) })
        homeChangesDuringRequest = changes
        _uiState.update { it.copy(isHomeSyncing = true) }
        try {
            val cached = if (automatic) {
                if (keepsHomePosition && (maxId == null || anchorId != null))
                    timelineRepository.getCachedHomeWindow(session).forSession(snapshot).getOrNull()
                        ?: timelineRepository.getCachedHomeTimeline(session, maxId, limit, anchorId).forSession(snapshot).getOrNull()
                else if (!keepsHomePosition) timelineRepository.getCachedHomeTimeline(session, maxId, limit, anchorId).forSession(snapshot).getOrNull()
                else null
            } else null
            if (cached != null) {
                _uiState.update { it.copy(isShowingSavedStatuses = true) }
                onCached(applyPending(cached))
            }
            if (automatic && !timelineRepository.isNetworkAvailable()) {
                _uiState.update { it.copy(isShowingSavedStatuses = it.statuses.isNotEmpty()) }
                return cached?.let { Result.success(applyPending(it)) }
                    ?: Result.failure(java.io.IOException("通信できません。接続後に再試行してください。"))
            }
            val result = (if (gap != null) requestGapPage(snapshot, feed, gap, limit)
                else timelineRepository.getTimeline(session, feed, maxId, limit)).forSession(snapshot)
            if (result.isSuccess) {
                blockedAutomaticCursor = null
                val page = result.getOrThrow()
                if (keepsHomePosition) {
                    if (page.homeIndex?.partial == true) return Result.failure(java.io.IOException("ホームを準備中です。再試行してください。"))
                    var previous = homeWindow()
                    if (anchorId != null && maxId != null && maxId !in previous.homeIndex().ids && previous.homeIndex().ids.isNotEmpty())
                        previous = previous.copy(homeIndex = previous.homeIndex().copy(ids = listOf(maxId) + previous.homeIndex().ids))
                    val merged = when {
                        gap != null -> previous.fillHomeGap(gap, page)
                        previous.homeIndex().ids.isEmpty() -> page.copy(homeIndex = page.homeIndex())
                        else -> previous.mergeHomePage(page, maxId)
                    }.let { window -> probe(previous, page, window) }
                        .let { window -> changes.fold(window) { value, change -> value.applyHomeWindowChange(change) } }
                    timelineRepository.saveHomeWindow(session, merged, savedViewport(session.sessionId)?.anchorId, changes.toList()).forSession(snapshot)
                    _uiState.update { it.copy(isShowingSavedStatuses = false) }
                    return Result.success(changes.fold(merged) { value, change -> value.applyHomeWindowChange(change) })
                }
                // Merge the response and in-flight changes in one serialized transaction.
                // Later events queue after this write; an older replay cannot undo them.
                timelineRepository.cacheHomeTimeline(session, maxId, page, changes.toList()).forSession(snapshot)
                _uiState.update { it.copy(isShowingSavedStatuses = false) }
                return Result.success(page.copy(statuses = changes.fold(page.statuses) { rows, change ->
                    rows.applyHomeChange(change, includeNew = maxId == null)
                }))
            }
            if (automatic && cached != null) return Result.success(applyPending(cached))
            return result
        } finally {
            if (homeChangesDuringRequest === changes) {
                homeChangesDuringRequest = null
                // A cancelled request for a previous feed must not touch its replacement.
                if (snapshot == currentSnapshot() && _uiState.value.selectedFeed == feed) {
                    _uiState.update { it.copy(isHomeSyncing = false) }
                }
            }
        }
    }

    private suspend fun requestGapPage(snapshot: BrowsingSession.Snapshot, feed: TimelineFeed,
        gap: HomeTimelineGap, limit: Int = 20): Result<TimelinePage> =
        timelineRepository.getTimeline(snapshot.account!!, feed, gap.maxId, limit).forSession(snapshot)

    private fun probeStreamGap(gap: HomeTimelineGap) {
        val snapshot = currentSnapshot() ?: return
        val ongoing = timelineJob
        streamGapProbeJob?.cancel()
        streamGapProbeJob = requestScope.launch {
            try {
                // Keep the new boundary out of the display until its one-page check settles.
                ongoing?.join()
                if (snapshot != currentSnapshot() || !keepsHomePosition ||
                    gap !in _uiState.value.homeIndex?.gaps.orEmpty()) return@launch
                loadGap(gap.beforeId, automatic = true)
                timelineJob?.join()
            } finally {
                if (snapshot == currentSnapshot()) _uiState.update {
                    if (it.automaticGapCheck == gap) it.copy(automaticGapCheck = null) else it
                }
            }
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
            offset = savedStateHandle.get<Int>(VIEWPORT_OFFSET) ?: 0,
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
        if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.FiltersChanged) {
            val snapshot = currentSnapshot() ?: return
            requestScope.launch {
                io.github.ponpokoo.mastodonclient.feature.common.trackServerFilterDisplay(timelineRepository, snapshot.account!!, "timeline") {
                    timelineJob?.join(); refresh(); timelineJob?.join()
                    _uiState.value.errorMessage == null && !_uiState.value.isShowingSavedStatuses
                }
            }
            return
        }
        if (change is BrowsingSession.Change.TagUpdated) return
        homeChangesDuringRequest?.add(change)
        if (change !is BrowsingSession.Change.Stream || change.event !is TimelineStreamEvent.NotificationReceived) currentSnapshot()?.let { snapshot -> requestScope.launch {
            timelineRepository.updateHomeTimelineCache(snapshot.account!!, change).forSession(snapshot)
        } }
        val accountId = currentSnapshot()?.account?.accountId
        val previousGaps = _uiState.value.homeIndex?.gaps.orEmpty()
        _uiState.update { current ->
            val next = when (change) {
                is BrowsingSession.Change.TagUpdated -> current
                is BrowsingSession.Change.StatusUpdated -> current.copy(statuses = current.statuses.applyHomeChange(change))
                is BrowsingSession.Change.StatusDeleted -> current.copy(statuses = current.statuses.filterNot { it.statusId == change.statusId }, unseenStreamIds = current.unseenStreamIds - current.statuses.filter { it.statusId == change.statusId }.map { it.timelineId }.toSet())
                is BrowsingSession.Change.Stream -> when (val event = change.event) {
                    is TimelineStreamEvent.StatusAdded -> if (current.selectedFeed == TimelineFeed.Home) {
                        val exists = current.statuses.any { it.timelineId == event.status.timelineId }
                        val isOwnActivity = event.status.isActivityBy(accountId)
                        if (event.isEdit) {
                            current.copy(statuses = current.statuses.map {
                                if (it.statusId == event.status.statusId) event.status.copy(timelineId = it.timelineId, boostedBy = it.boostedBy, createdAt = it.createdAt) else it
                            })
                        } else if (exists) {
                            current.copy(statuses = current.statuses.map { if (it.timelineId == event.status.timelineId) event.status else it })
                        } else if (current.isResumedWindow && !isOwnActivity) {
                            current.copy(unseenStreamIds = current.unseenStreamIds + event.status.timelineId)
                        } else {
                            current.copy(
                                statuses = listOf(event.status) + current.statuses,
                                unseenStreamIds = when {
                                    followingTop -> emptySet()
                                    isOwnActivity -> current.unseenStreamIds
                                    else -> current.unseenStreamIds + event.status.timelineId
                                },
                                streamAutoScrollId = if (followingTop && !current.isRefreshing && !current.isHomeSyncing) ++streamSequence else current.streamAutoScrollId,
                                streamAtTopCount = when {
                                    !followingTop -> null
                                    isOwnActivity -> current.streamAtTopCount
                                    else -> (current.streamAtTopCount ?: 0) + 1
                                },
                            )
                        }
                    } else current
                    is TimelineStreamEvent.StatusDeleted -> current.copy(statuses = current.statuses.filterNot { it.statusId == event.statusId || it.timelineId == event.statusId }, unseenStreamIds = current.unseenStreamIds - current.statuses.filter { it.statusId == event.statusId || it.timelineId == event.statusId }.map { it.timelineId }.toSet())
                    is TimelineStreamEvent.FiltersChanged, is TimelineStreamEvent.Connected -> current
                    is TimelineStreamEvent.NotificationReceived -> current
                }
            }
            if (keepsHomePosition) {
                val window = TimelinePage(current.statuses, current.nextMaxId, current.endReached, current.homeIndex).applyHomeWindowChange(change)
                val newGap = if (change is BrowsingSession.Change.Stream &&
                    change.event is TimelineStreamEvent.StatusAdded)
                    window.homeIndex?.gaps?.firstOrNull { it !in previousGaps && !it.unavailable }
                else null
                next.copy(statuses = window.statuses, homeIndex = window.homeIndex,
                    automaticGapCheck = newGap ?: current.automaticGapCheck)
            } else next
        }
        if (keepsHomePosition && change is BrowsingSession.Change.Stream &&
            change.event is TimelineStreamEvent.StatusAdded) {
            _uiState.value.homeIndex?.gaps?.firstOrNull { it !in previousGaps && !it.unavailable }
                ?.let(::probeStreamGap)
        }
    }

    private fun showError(
        error: Throwable,
        initial: Boolean = false,
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
        automatic: Boolean = false,
    ) {
        val message = when (error.requestFailure) {
            RequestFailure.Unauthorized -> "ログインの有効期限が切れました。ログインし直してください。"
            RequestFailure.RateLimited -> "アクセスが集中しています。しばらく待ってから再試行してください。"
            else -> error.message ?: "ホームタイムラインを取得できませんでした。"
        }
        _uiState.update {
            it.copy(
                isInitialLoading = if (initial) false else it.isInitialLoading,
                isRefreshing = if (refreshing) false else it.isRefreshing,
                isLoadingMore = if (loadingMore) false else it.isLoadingMore,
                errorMessage = if (automatic && it.selectedFeed == TimelineFeed.Home && it.statuses.isNotEmpty()) null else message,
                isShowingSavedStatuses = it.isShowingSavedStatuses || (automatic && it.selectedFeed == TimelineFeed.Home && it.statuses.isNotEmpty()),
            )
        }
    }

}

private fun TimelineStatus.isActivityBy(accountId: String?): Boolean =
    accountId != null && (boostedBy ?: author).id == accountId
