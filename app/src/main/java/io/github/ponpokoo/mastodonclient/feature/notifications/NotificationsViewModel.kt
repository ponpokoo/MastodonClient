package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.requiresAuthentication
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class NotificationListState(
    val notifications: List<TimelineNotification> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val errorIsPagination: Boolean = false,
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoaded: Boolean = false,
    val emptyAutoPages: Int = 0,
    val serverFiltered: Boolean = true,
) {
    val canAutoLoad: Boolean get() = isLoaded && !isLoading && !isLoadingMore && error == null &&
        !endReached && nextMaxId != null && emptyAutoPages < 2
}

data class NotificationsUiState(
    // The all-notifications state also supplies the global badge. Other tabs have independent pages.
    val notifications: List<TimelineNotification> = emptyList(),
    val isLoadingNotifications: Boolean = false,
    val isPullRefreshingNotifications: Boolean = false,
    val notificationsError: String? = null,
    val notificationsErrorIsPagination: Boolean = false,
    val notificationsNextMaxId: String? = null,
    val notificationsEndReached: Boolean = false,
    val isLoadingMoreNotifications: Boolean = false,
    val isInitialPageLoaded: Boolean = false,
    val pendingNewNotificationIds: Set<String> = emptySet(),
    val highlightedNotificationIds: Set<String> = emptySet(),
    val shownNewNotice: ShownNewNotice? = null,
    val refreshResult: NotificationsRefreshResult? = null,
    val lists: Map<NotificationCategory, NotificationListState> = emptyMap(),
    val selectedCategory: NotificationCategory = NotificationCategory.All,
    val capabilities: NotificationCapabilities? = null,
    val isCheckingCapabilities: Boolean = false,
    val isReadStateReady: Boolean = false,
    val isResolvingReadState: Boolean = false,
    val allEmptyAutoPages: Int = 0,
) {
    val unreadNotifications: Int get() = if (isResolvingReadState) 0 else pendingNewNotificationIds.size
    val canShowNewNotice: Boolean get() = !isResolvingReadState

    fun list(category: NotificationCategory): NotificationListState = if (category == NotificationCategory.All) {
        NotificationListState(notifications, isLoadingNotifications, isPullRefreshingNotifications, notificationsError,
            notificationsErrorIsPagination, notificationsNextMaxId, notificationsEndReached,
            isLoadingMoreNotifications, isInitialPageLoaded, allEmptyAutoPages)
    } else lists[category] ?: NotificationListState()

    fun canExplore(category: NotificationCategory): Boolean = category != NotificationCategory.Reactions ||
        (!isCheckingCapabilities && capabilities?.supportsEmojiReactions == true)

    fun forCategory(category: NotificationCategory): NotificationsUiState {
        val tab = list(category)
        return copy(notifications = tab.notifications, isLoadingNotifications = tab.isLoading,
            isPullRefreshingNotifications = tab.isRefreshing, notificationsError = tab.error,
            notificationsErrorIsPagination = tab.errorIsPagination, notificationsNextMaxId = tab.nextMaxId,
            notificationsEndReached = tab.endReached, isLoadingMoreNotifications = tab.isLoadingMore,
            isInitialPageLoaded = tab.isLoaded)
    }

    fun withList(category: NotificationCategory, tab: NotificationListState): NotificationsUiState =
        if (category == NotificationCategory.All) copy(notifications = tab.notifications,
            isLoadingNotifications = tab.isLoading, isPullRefreshingNotifications = tab.isRefreshing,
            notificationsError = tab.error, notificationsErrorIsPagination = tab.errorIsPagination,
            notificationsNextMaxId = tab.nextMaxId, notificationsEndReached = tab.endReached,
            isLoadingMoreNotifications = tab.isLoadingMore, isInitialPageLoaded = tab.isLoaded,
            allEmptyAutoPages = tab.emptyAutoPages)
        else copy(lists = lists + (category to tab))
}

data class ShownNewNotice(val id: Long, val count: Int)
data class NotificationsRefreshResult(val id: Long, val hasNewNotifications: Boolean)

class NotificationsViewModel(
    private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession,
    private val systemNotifications: SystemNotificationRepository? = null,
    private val pushSync: io.github.ponpokoo.mastodonclient.domain.repository.PushSyncRepository? = null,
) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private val pageJobs = mutableMapOf<NotificationCategory, Job>()
    private val cacheJobs = mutableMapOf<NotificationCategory, Job>()
    private val restoredCategories = mutableSetOf<NotificationCategory>()
    private val requestedCategories = mutableSetOf<NotificationCategory>()
    private var cacheReady = CompletableDeferred<Unit>()
    private var readJob: Job? = null
    private var capabilityJob: Job? = null
    private var capabilityCheckRequested = false
    private var readWriteJob: Job? = null
    private var markerWriteJob: Job? = null
    private var readState = NotificationReadState()
    private var markerId: String? = null
    private var initialAllPage = emptyList<TimelineNotification>()
    private var waitingAllMarkerId: String? = null
    private var lastSentMarkerId: String? = null
    private var reactionRefreshRequested = false
    private var reactionPullRefreshRequested = false
    private var nextRefreshResultId = 0L
    private var nextShownNoticeId = 0L
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        val selected = _uiState.value.selectedCategory
        _uiState.value = NotificationsUiState(selectedCategory = selected)
        pageJobs.clear(); cacheJobs.clear(); restoredCategories.clear()
        cacheReady = CompletableDeferred()
        readJob = null; capabilityJob = null; readWriteJob = null; markerWriteJob = null
        capabilityCheckRequested = false
        readState = NotificationReadState(); markerId = null; initialAllPage = emptyList()
        waitingAllMarkerId = null; lastSentMarkerId = null
        reactionRefreshRequested = false; reactionPullRefreshRequested = false
        if (requestedCategories.isNotEmpty() && snapshot.account != null) {
            requestedCategories.toList().forEach { loadCategory(it) }
        }
    }

    private fun updateList(category: NotificationCategory, transform: (NotificationListState) -> NotificationListState) {
        _uiState.update { it.withList(category, transform(it.list(category))) }
    }

    fun selectCategory(category: NotificationCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
        if (requestedCategories.isEmpty()) return
        loadCategory(category)
    }

    fun loadNotifications(force: Boolean = false, showPullRefreshIndicator: Boolean = false) =
        loadCategory(_uiState.value.selectedCategory, force, showPullRefreshIndicator)

    private fun ensureReadState(snapshot: BrowsingSession.Snapshot) {
        if (readJob != null) return
        _uiState.update { it.copy(isResolvingReadState = true) }
        readJob = requestScope.launch {
            val session = snapshot.account!!
            val remoteMarker = async {
                withTimeoutOrNull(5_000) { timelineRepository.getNotificationMarker(session).forSession(snapshot) }
            }
            val cached = timelineRepository.getCachedNotifications(session).forSession(snapshot).getOrNull()
            markerId = cached?.lastReadId
            readState = NotificationReadState((readState.viewedIds + cached?.readState?.viewedIds.orEmpty()).distinct().take(1000),
                readState.latestViewedAllId ?: cached?.readState?.latestViewedAllId)
            _uiState.update { it.copy(pendingNewNotificationIds = it.pendingNewNotificationIds - readState.viewedIds.toSet(),
                highlightedNotificationIds = it.highlightedNotificationIds - readState.viewedIds.toSet()) }
            if (cached != null && !_uiState.value.isInitialPageLoaded) {
                updateList(NotificationCategory.All) { it.copy(notifications =
                    (it.notifications + cached.notifications).distinctBy(TimelineNotification::id)) }
            }
            restoredCategories += NotificationCategory.All
            cacheReady.complete(Unit)
            if (_uiState.value.pendingNewNotificationIds.isNotEmpty()) persistNotifications(snapshot, NotificationCategory.All)
            markerId = remoteMarker.await()?.getOrElse { markerId } ?: markerId
            _uiState.update { it.copy(isReadStateReady = true, isResolvingReadState = false) }
            applyInitialUnread()
            persistReadState(snapshot)
            waitingAllMarkerId?.let { sendMarker(snapshot, it) }
        }
    }

    private fun checkCapabilities(force: Boolean = false, reloadReactions: Boolean = false, pullRefresh: Boolean = false) {
        val snapshot = currentSnapshot() ?: return
        reactionRefreshRequested = reactionRefreshRequested || reloadReactions
        reactionPullRefreshRequested = reactionPullRefreshRequested || pullRefresh
        if (capabilityJob?.isActive == true || !force && capabilityCheckRequested) return
        capabilityCheckRequested = true
        _uiState.update { it.copy(isCheckingCapabilities = true) }
        capabilityJob = requestScope.launch {
            val result = withTimeoutOrNull(5_000) {
                timelineRepository.getNotificationCapabilities(snapshot.account!!).forSession(snapshot)
            }
            _uiState.update { it.copy(capabilities = result?.getOrNull(), isCheckingCapabilities = false) }
            val refresh = reactionRefreshRequested
            val pull = reactionPullRefreshRequested
            reactionRefreshRequested = false; reactionPullRefreshRequested = false
            if (_uiState.value.capabilities?.supportsEmojiReactions != true) {
                pageJobs.remove(NotificationCategory.Reactions)?.cancel()
                updateList(NotificationCategory.Reactions) { it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false) }
            } else if (NotificationCategory.Reactions in requestedCategories) {
                loadCategory(NotificationCategory.Reactions, force = refresh, showPullRefreshIndicator = pull)
            }
        }
    }

    private fun loadCategory(category: NotificationCategory, force: Boolean = false, showPullRefreshIndicator: Boolean = false) {
        requestedCategories += category
        val snapshot = currentSnapshot() ?: return
        ensureReadState(snapshot)
        checkCapabilities()
        if (!_uiState.value.canExplore(category)) return
        val before = _uiState.value.list(category)
        if (before.isLoading || !force && before.isLoaded) return
        val initial = !before.isLoaded
        if (initial || force) requestScope.launch {
            io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable { pushSync?.recover(snapshot.account!!.sessionId, false) }
        }
        val boundary = before.notifications.firstOrNull()?.id
        val pendingAtStart = _uiState.value.pendingNewNotificationIds
        pageJobs[category]?.cancel()
        updateList(category) { it.copy(isLoading = true, isRefreshing = showPullRefreshIndicator,
            isLoadingMore = false, error = null, errorIsPagination = false) }
        pageJobs[category] = requestScope.launch {
            // Start REST immediately; only disk restoration, never the read marker, precedes its display.
            val pageRequest = async {
                if (category != NotificationCategory.All) capabilityJob?.join()
                timelineRepository.getNotificationPage(snapshot.account!!, category,
                    supportsTypeFiltering = _uiState.value.capabilities?.supportsTypeFiltering == true)
            }
            cacheReady.await()
            if (category !in restoredCategories) {
                val cached = timelineRepository.getCachedNotificationCategory(snapshot.account!!, category).forSession(snapshot).getOrNull()
                restoredCategories += category
                if (cached != null) updateList(category) { it.copy(notifications =
                    (it.notifications + cached.notifications).distinctBy(TimelineNotification::id)) }
            }
            pageRequest.await().forSession(snapshot).onSuccess { page ->
                updateList(category) { tab ->
                    val retained = if (initial) tab.notifications.filter { it.id in _uiState.value.pendingNewNotificationIds }
                        else tab.notifications
                    tab.copy(notifications = (page.notifications + retained).distinctBy(TimelineNotification::id)
                        .sortedByDescending(TimelineNotification::createdAt), isLoading = false, isRefreshing = false,
                        isLoaded = true, nextMaxId = page.nextMaxId, endReached = page.endReached,
                        emptyAutoPages = if (page.notifications.isEmpty()) 1 else 0, serverFiltered = page.serverFiltered)
                }
                if (category == NotificationCategory.All) {
                    if (initial || !_uiState.value.isReadStateReady) { initialAllPage = page.notifications; applyInitialUnread() }
                    else addUnread(beforeBoundary(page.notifications, boundary))
                    val streamed = _uiState.value.pendingNewNotificationIds - pendingAtStart
                    _uiState.update { it.copy(highlightedNotificationIds = it.pendingNewNotificationIds + streamed,
                        refreshResult = NotificationsRefreshResult(++nextRefreshResultId, it.pendingNewNotificationIds.isNotEmpty())) }
                }
                persistNotifications(snapshot, category)
            }.onFailure { error ->
                val message = if (error.requiresAuthentication)
                    "通知を表示するには、ログアウト後に再ログインして通知の読み取りを許可してください。"
                else error.message ?: "通知を取得できませんでした"
                updateList(category) { it.copy(isLoading = false, isRefreshing = false, error = message) }
            }
        }
    }

    /** A boundary must occur in the server-ordered page; opaque IDs are never compared numerically. */
    private fun beforeBoundary(page: List<TimelineNotification>, boundary: String?): Set<String> {
        val index = page.indexOfFirst { it.id == boundary }
        return if (boundary == null || index < 0) emptySet() else page.take(index).mapTo(mutableSetOf(), TimelineNotification::id)
    }

    private fun applyInitialUnread() {
        if (!_uiState.value.isReadStateReady) return
        val local = readState.latestViewedAllId
        val localIndex = initialAllPage.indexOfFirst { it.id == local }
        val remoteIndex = initialAllPage.indexOfFirst { it.id == markerId }
        val boundary = if (local == null) markerId else if (localIndex >= 0 && remoteIndex in 0 until localIndex) markerId else local
        addUnread(beforeBoundary(initialAllPage, boundary))
    }

    private fun addUnread(ids: Set<String>) {
        val confirmed = ids - readState.viewedIds.toSet()
        _uiState.update { it.copy(pendingNewNotificationIds = it.pendingNewNotificationIds + confirmed,
            highlightedNotificationIds = it.highlightedNotificationIds + confirmed) }
    }

    fun onNotificationsVisible() = loadNotifications()
    fun onAppForeground() {
        if (requestedCategories.isNotEmpty()) {
            checkCapabilities(force = true, reloadReactions = true)
            requestedCategories.filter { it != NotificationCategory.Reactions }.forEach { loadCategory(it, force = true) }
        }
    }
    fun onSystemNotificationOpened() { selectCategory(NotificationCategory.All); loadCategory(NotificationCategory.All, force = true) }
    fun refreshNotifications() {
        checkCapabilities(force = true, reloadReactions = true, pullRefresh = true)
        if (_uiState.value.selectedCategory != NotificationCategory.Reactions) {
            loadNotifications(force = true, showPullRefreshIndicator = true)
        }
    }
    fun onNotificationsHidden() { _uiState.update { it.copy(highlightedNotificationIds = emptySet(), shownNewNotice = null) } }

    fun onLatestNotificationsShown(shownIds: Set<String>, allNotificationsShown: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val category = if (allNotificationsShown) NotificationCategory.All else _uiState.value.selectedCategory
        val tab = _uiState.value.list(category)
        if (shownIds.isEmpty() || !tab.isLoaded || tab.isLoading || tab.error != null && !tab.errorIsPagination) return
        var ids = tab.notifications.map { it.id }.filterTo(mutableSetOf()) { it in shownIds }
        if (ids.isEmpty()) return
        val latestId = tab.notifications.firstOrNull()?.id?.takeIf { allNotificationsShown && it in ids }
        // Advancing the global marker also acknowledges older known rows. A filtered tab does not.
        if (latestId != null) ids = tab.notifications.mapTo(mutableSetOf(), TimelineNotification::id)
        readState = NotificationReadState((ids.toList() + readState.viewedIds).distinct().take(1000),
            latestId ?: readState.latestViewedAllId)
        persistReadState(snapshot)
        requestScope.launch {
            try { systemNotifications?.dismissRead(snapshot.account!!.sessionId, ids) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Dismissal failure must not prevent in-app acknowledgement. */ }
        }
        val acknowledged = _uiState.value.pendingNewNotificationIds.intersect(ids)
        if (acknowledged.isNotEmpty()) _uiState.update { it.copy(
            pendingNewNotificationIds = it.pendingNewNotificationIds - acknowledged,
            shownNewNotice = ShownNewNotice(++nextShownNoticeId, acknowledged.size)) }
        if (latestId != null) {
            waitingAllMarkerId = latestId
            if (_uiState.value.isReadStateReady) sendMarker(snapshot, latestId)
        }
    }

    private fun sendMarker(snapshot: BrowsingSession.Snapshot, id: String) {
        if (id == lastSentMarkerId) return
        lastSentMarkerId = id
        markerWriteJob?.cancel()
        markerWriteJob = requestScope.launch {
            val result = timelineRepository.saveNotificationMarker(snapshot.account!!, id).forSession(snapshot)
            if (result.isFailure && lastSentMarkerId == id) lastSentMarkerId = null
        }
    }

    fun consumeShownNewNotice(id: Long) { _uiState.update { if (it.shownNewNotice?.id == id) it.copy(shownNewNotice = null) else it } }
    fun consumeRefreshResult(id: Long) { _uiState.update { if (it.refreshResult?.id == id) it.copy(refreshResult = null) else it } }
    fun loadNextNotifications() = loadNext(automatic = false)
    fun loadNextNotificationsAutomatically() = loadNext(automatic = true)

    private fun loadNext(automatic: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val category = _uiState.value.selectedCategory
        val tab = _uiState.value.list(category)
        val cursor = tab.nextMaxId ?: return
        if (!_uiState.value.canExplore(category) || tab.isLoading || tab.isLoadingMore || tab.endReached ||
            automatic && !tab.canAutoLoad) return
        updateList(category) { it.copy(isLoadingMore = true, error = null, errorIsPagination = false) }
        pageJobs[category] = requestScope.launch {
            timelineRepository.getNotificationPage(snapshot.account!!, category, cursor,
                supportsTypeFiltering = tab.serverFiltered && _uiState.value.capabilities?.supportsTypeFiltering == true)
                .forSession(snapshot).onSuccess { page ->
                    updateList(category) { current ->
                        val added = page.notifications.filterNot { n -> current.notifications.any { it.id == n.id } }
                        current.copy(notifications = (current.notifications + page.notifications).distinctBy(TimelineNotification::id),
                            isLoadingMore = false, nextMaxId = page.nextMaxId,
                            endReached = page.endReached || page.nextMaxId == cursor,
                            emptyAutoPages = if (added.isEmpty()) (if (automatic) current.emptyAutoPages else 0) + 1 else 0,
                            serverFiltered = page.serverFiltered)
                    }
                    persistNotifications(snapshot, category)
                }.onFailure { error -> updateList(category) { it.copy(isLoadingMore = false,
                    error = error.message ?: "通知の続きを取得できませんでした", errorIsPagination = true) } }
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.NotificationReceived) {
            val notification = change.event.notification
            val known = NotificationCategory.entries.any { category -> _uiState.value.list(category).notifications.any { it.id == notification.id } }
            NotificationCategory.entries.filter { it.includes(notification) }.forEach { category ->
                updateList(category) { it.copy(notifications = (listOf(notification) + it.notifications).distinctBy(TimelineNotification::id)) }
            }
            if (!known && notification.id !in readState.viewedIds) addUnread(setOf(notification.id))
        } else {
            val deleted = when (change) {
                is BrowsingSession.Change.StatusDeleted -> change.statusId
                is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
                else -> null
            }
            for (category in NotificationCategory.entries) updateList(category) { tab ->
                tab.copy(notifications = tab.notifications.map { notification -> notification.copy(status = notification.status?.let { status ->
                    when {
                        status.statusId == deleted -> null
                        change is BrowsingSession.Change.StatusUpdated -> status.withUpdatedActions(change.status)
                        else -> status
                    }
                }) })
            }
        }
        currentSnapshot()?.let { snapshot -> restoredCategories.forEach { persistNotifications(snapshot, it) } }
    }

    private fun persistNotifications(snapshot: BrowsingSession.Snapshot, category: NotificationCategory) {
        val notifications = _uiState.value.list(category).notifications
        cacheJobs[category]?.cancel()
        cacheJobs[category] = requestScope.launch {
            timelineRepository.cacheNotificationCategory(snapshot.account!!, category, notifications).forSession(snapshot)
        }
    }

    private fun persistReadState(snapshot: BrowsingSession.Snapshot) {
        if (!cacheReady.isCompleted) return
        val value = readState
        readWriteJob?.cancel()
        readWriteJob = requestScope.launch { timelineRepository.saveNotificationReadState(snapshot.account!!, value).forSession(snapshot) }
    }
}
