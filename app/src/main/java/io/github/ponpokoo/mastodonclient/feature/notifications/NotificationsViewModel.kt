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
import java.time.Instant
import java.time.format.DateTimeParseException
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
        capabilities?.supportsEmojiReactions == true

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

data class NotificationsRefreshResult(val id: Long, val hasNewNotifications: Boolean)

class NotificationsViewModel(
    private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession,
    private val systemNotifications: SystemNotificationRepository? = null,
    private val pushSync: io.github.ponpokoo.mastodonclient.domain.repository.PushSyncRepository? = null,
    private val capabilityClock: () -> Long = System::currentTimeMillis,
) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState = _uiState.moderated(viewModelScope, timelineRepository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private val pageJobs = mutableMapOf<NotificationCategory, Job>()
    private data class PendingRefresh(val pull: Boolean, val keepHighlights: Boolean)
    private val pendingRefreshes = mutableMapOf<NotificationCategory, PendingRefresh>()
    private val cacheJobs = mutableMapOf<NotificationCategory, Job>()
    private val restoredCategories = mutableSetOf<NotificationCategory>()
    private val requestedCategories = mutableSetOf<NotificationCategory>()
    private var cacheReady = CompletableDeferred<Unit>()
    private var readJob: Job? = null
    private var capabilityJob: Job? = null
    private var capabilityCheckRequested = false
    private var capabilityCheckedAt = 0L
    private var readWriteJob: Job? = null
    private var markerWriteJob: Job? = null
    private var readState = NotificationReadState()
    private var initialReadState = NotificationReadState()
    private var allowInitialHighlights = true
    private var markerId: String? = null
    private var initialAllPage = emptyList<TimelineNotification>()
    private var waitingAllMarkerId: String? = null
    private var lastSentMarkerId: String? = null
    private var reactionRefreshRequested = false
    private var reactionPullRefreshRequested = false
    private var nextRefreshResultId = 0L
    private var hasObservedStart = false
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        val selected = _uiState.value.selectedCategory
        _uiState.value = NotificationsUiState(selectedCategory = selected)
        pageJobs.clear(); pendingRefreshes.clear(); cacheJobs.clear(); restoredCategories.clear()
        cacheReady = CompletableDeferred()
        readJob = null; capabilityJob = null; readWriteJob = null; markerWriteJob = null
        capabilityCheckRequested = false
        capabilityCheckedAt = 0L
        readState = NotificationReadState(); markerId = null; initialAllPage = emptyList()
        initialReadState = NotificationReadState(); allowInitialHighlights = true
        waitingAllMarkerId = null; lastSentMarkerId = null
        reactionRefreshRequested = false; reactionPullRefreshRequested = false
        snapshot.account?.let { account ->
            requestScope.launch {
                timelineRepository.observeServerCapabilities(account).collect { capabilities ->
                    Result.success(capabilities).forSession(snapshot)
                    val previous = _uiState.value.capabilities
                    _uiState.update { it.copy(capabilities = capabilities) }
                    if (!capabilities.supportsEmojiReactions) {
                        pageJobs.remove(NotificationCategory.Reactions)?.cancel()
                        updateList(NotificationCategory.Reactions) { it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false) }
                    } else if (previous?.supportsEmojiReactions != true && NotificationCategory.Reactions in requestedCategories) {
                        loadCategory(NotificationCategory.Reactions)
                    }
                }
            }
            checkCapabilities()
            // Warm the default list and read state while the home tab is visible.
            // Only displayed rows acknowledge notifications.
            requestedCategories += NotificationCategory.All
        }
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
            // Keep the pre-display boundary even if rows are acknowledged before the remote marker arrives.
            initialReadState = cached?.readState ?: NotificationReadState()
            readState = NotificationReadState((readState.viewedIds + cached?.readState?.viewedIds.orEmpty()).distinct().take(1000),
                readState.latestViewedAllId ?: cached?.readState?.latestViewedAllId)
            _uiState.update { it.copy(pendingNewNotificationIds = it.pendingNewNotificationIds - readState.viewedIds.toSet(),
                highlightedNotificationIds = it.highlightedNotificationIds - readState.viewedIds.toSet()) }
            if (cached != null && !_uiState.value.isInitialPageLoaded) {
                updateList(NotificationCategory.All) { it.copy(notifications =
                    mergeNotifications(it.notifications, cached.notifications)) }
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

    private fun checkCapabilities() {
        val snapshot = currentSnapshot() ?: return
        val now = capabilityClock()
        val capabilities = _uiState.value.capabilities
        val age = now - capabilityCheckedAt
        val ttl = if (capabilities?.reactionApi == EmojiReactionApi.Unknown || capabilities == null)
            NotificationCapabilities.RETRY_MILLIS else NotificationCapabilities.TTL_MILLIS
        val fresh = if (capabilities?.retryAtEpochMillis != null && capabilities.retryAtEpochMillis > 0)
            capabilities.isRetryPending(now) else age in 0 until ttl
        if (capabilityJob?.isActive == true || capabilityCheckRequested && fresh) return
        capabilityCheckRequested = true
        capabilityCheckedAt = capabilityClock()
        _uiState.update { it.copy(isCheckingCapabilities = true) }
        capabilityJob = requestScope.launch {
            val previous = _uiState.value.capabilities?.takeIf { it.reactionApi != EmojiReactionApi.Unknown }
            val result = withTimeoutOrNull(5_000) {
                timelineRepository.getServerCapabilities(snapshot.account!!).forSession(snapshot)
            }
            val fetched = result?.getOrNull()
            val resolved = if (fetched == null || fetched.reactionApi == EmojiReactionApi.Unknown)
                previous?.copy(retryAtEpochMillis = capabilityClock() + NotificationCapabilities.RETRY_MILLIS) ?: fetched
                else fetched
            _uiState.update { it.copy(capabilities = resolved, isCheckingCapabilities = false) }
            capabilityCheckedAt = resolved?.checkedAtEpochMillis?.takeIf { it > 0 } ?: capabilityClock()
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

    private fun loadCategory(category: NotificationCategory, force: Boolean = false, showPullRefreshIndicator: Boolean = false,
        keepHighlights: Boolean = false) {
        requestedCategories += category
        val snapshot = currentSnapshot() ?: return
        ensureReadState(snapshot)
        checkCapabilities()
        if (!_uiState.value.canExplore(category)) {
            if (category == NotificationCategory.Reactions && _uiState.value.isCheckingCapabilities) {
                reactionRefreshRequested = reactionRefreshRequested || force
                reactionPullRefreshRequested = reactionPullRefreshRequested || showPullRefreshIndicator
            }
            return
        }
        val before = _uiState.value.list(category)
        if (before.isLoading) {
            if (force) {
                // A notification tap or foreground return may arrive after this request's
                // server snapshot. Coalesce these triggers, then fetch once more.
                val pending = pendingRefreshes[category]
                pendingRefreshes[category] = PendingRefresh(pending?.pull == true || showPullRefreshIndicator,
                    (pending?.keepHighlights ?: true) && keepHighlights)
                if (showPullRefreshIndicator) updateList(category) { it.copy(isRefreshing = true) }
            }
            return
        }
        if (!force && before.isLoaded) return
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
                    mergeNotifications(it.notifications, cached.notifications)) }
            }
            pageRequest.await().forSession(snapshot).onSuccess { page ->
                updateList(category) { tab ->
                    // Only join restored history after the fresh page's tail. Keeping the
                    // whole snapshot could hide a gap or resurrect rows omitted by REST.
                    val overlap = if (!page.endReached) {
                        tab.notifications.indexOfFirst { it.id == page.notifications.lastOrNull()?.id }
                    } else -1
                    val restoredTail = if (initial && overlap >= 0) tab.notifications.drop(overlap + 1) else emptyList()
                    val retained = if (initial) tab.notifications.filter { it.id in _uiState.value.pendingNewNotificationIds }
                        else tab.notifications
                    val nextMaxId = if (initial) restoredTail.lastOrNull()?.id ?: page.nextMaxId
                        else if (overlap >= 0) tab.nextMaxId ?: page.nextMaxId else page.nextMaxId
                    tab.copy(notifications = mergeNotifications(page.notifications, retained, restoredTail),
                        isLoading = false, isRefreshing = false,
                        isLoaded = true, nextMaxId = nextMaxId, endReached = page.endReached,
                        emptyAutoPages = if (page.notifications.isEmpty()) 1 else 0, serverFiltered = page.serverFiltered)
                }
                if (category == NotificationCategory.All) {
                    if (initial || !_uiState.value.isReadStateReady) { initialAllPage = page.notifications; applyInitialUnread() }
                    else addUnread(beforeBoundary(page.notifications, boundary))
                    val streamed = _uiState.value.pendingNewNotificationIds - pendingAtStart
                    _uiState.update { it.copy(highlightedNotificationIds = it.pendingNewNotificationIds + streamed +
                        (if (keepHighlights) it.highlightedNotificationIds else emptySet()),
                        refreshResult = NotificationsRefreshResult(++nextRefreshResultId, it.pendingNewNotificationIds.isNotEmpty())) }
                }
                persistNotifications(snapshot, category)
            }.onFailure { error ->
                val message = if (error.requiresAuthentication)
                    "もう一度ログインして、必要な権限を許可してください。"
                else error.message ?: "通知を取得できませんでした"
                updateList(category) { it.copy(isLoading = false, isRefreshing = false, error = message) }
            }
            pendingRefreshes.remove(category)?.let { refresh ->
                loadCategory(category, force = true, showPullRefreshIndicator = refresh.pull, keepHighlights = refresh.keepHighlights)
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
        val local = initialReadState.latestViewedAllId
        val localIndex = initialAllPage.indexOfFirst { it.id == local }
        val remoteIndex = initialAllPage.indexOfFirst { it.id == markerId }
        val boundary = if (local == null) markerId else if (localIndex >= 0 && remoteIndex in 0 until localIndex) markerId else local
        val initiallyNew = beforeBoundary(initialAllPage, boundary) - initialReadState.viewedIds.toSet()
        addUnread(initiallyNew)
        if (allowInitialHighlights) _uiState.update {
            it.copy(highlightedNotificationIds = it.highlightedNotificationIds + initiallyNew)
        }
    }

    private fun addUnread(ids: Set<String>) {
        val confirmed = ids - readState.viewedIds.toSet()
        _uiState.update { it.copy(pendingNewNotificationIds = it.pendingNewNotificationIds + confirmed,
            highlightedNotificationIds = it.highlightedNotificationIds + confirmed) }
    }

    fun onNotificationsVisible() = loadCategory(_uiState.value.selectedCategory, force = true, keepHighlights = true)
    fun onLifecycleStarted() {
        // Keep this across observer reattachment: navigation or Activity recreation must
        // still refresh an existing list. Only the first start uses session prefetch alone.
        if (hasObservedStart) onAppForeground()
        hasObservedStart = true
    }
    fun onAppForeground() {
        if (requestedCategories.isNotEmpty()) {
            requestedCategories.toList().forEach { loadCategory(it, force = true) }
        }
    }
    fun onSystemNotificationOpened() { selectCategory(NotificationCategory.All); loadCategory(NotificationCategory.All, force = true, keepHighlights = true) }
    fun refreshNotifications() {
        loadNotifications(force = true, showPullRefreshIndicator = true)
    }
    fun onNotificationsHidden() {
        allowInitialHighlights = false
        _uiState.update { it.copy(highlightedNotificationIds = emptySet()) }
    }

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
            pendingNewNotificationIds = it.pendingNewNotificationIds - acknowledged) }
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
                        current.copy(notifications = mergeNotifications(current.notifications, page.notifications),
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
        if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.FiltersChanged) {
            val snapshot = currentSnapshot() ?: return
            if (requestedCategories.isEmpty()) return
            requestScope.launch {
                io.github.ponpokoo.mastodonclient.feature.common.trackServerFilterDisplay(timelineRepository, snapshot.account!!, "notifications") {
                    pageJobs.values.toList().forEach { it.join() }
                    requestedCategories.toList().forEach { loadCategory(it, force = true) }
                    pageJobs.values.toList().forEach { it.join() }
                    requestedCategories.all { _uiState.value.list(it).error == null }
                }
            }
            return
        }
        val notification = ((change as? BrowsingSession.Change.Stream)?.event as? TimelineStreamEvent.NotificationReceived)?.notification
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        // Ordinary timeline posts, connection events and tag changes cannot affect notifications.
        if (notification == null && deleted == null && change !is BrowsingSession.Change.StatusUpdated) return
        val before = _uiState.value
        if (notification != null) {
            val known = NotificationCategory.entries.any { category -> _uiState.value.list(category).notifications.any { it.id == notification.id } }
            NotificationCategory.entries.filter { it.includes(notification) }.forEach { category ->
                updateList(category) { it.copy(notifications = mergeNotifications(listOf(notification), it.notifications)) }
            }
            if (!known && notification.id !in readState.viewedIds) addUnread(setOf(notification.id))
        } else {
            for (category in NotificationCategory.entries) updateList(category) { tab ->
                tab.copy(notifications = tab.notifications.map { notification ->
                    val status = notification.status
                    val updated = when {
                        status == null -> null
                        status.statusId == deleted -> null
                        change is BrowsingSession.Change.StatusUpdated -> status.withUpdatedActions(change.status)
                        else -> status
                    }
                    if (updated == status) notification else notification.copy(status = updated)
                })
            }
        }
        val after = _uiState.value
        currentSnapshot()?.let { snapshot ->
            restoredCategories.forEach { category ->
                if (before.list(category).notifications != after.list(category).notifications) persistNotifications(snapshot, category)
            }
        }
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

/** First source wins duplicate IDs and ties; refreshes put the server page first. */
private fun mergeNotifications(vararg sources: List<TimelineNotification>): List<TimelineNotification> =
    sources.flatMap { it }.distinctBy(TimelineNotification::id)
        .map { notification ->
            val time = try { Instant.parse(notification.createdAt) }
                catch (_: DateTimeParseException) { null }
            notification to time
        }
        // Stable sort preserves source order for equal instants and puts unparseable dates last.
        .sortedByDescending { it.second }
        .map { it.first }
