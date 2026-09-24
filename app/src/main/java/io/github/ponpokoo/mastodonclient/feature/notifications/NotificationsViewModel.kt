package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
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

data class NotificationsUiState(
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
) {
    val unreadNotifications: Int get() = pendingNewNotificationIds.size
}

data class ShownNewNotice(val id: Long, val count: Int)

data class NotificationsRefreshResult(
    val id: Long,
    val hasNewNotifications: Boolean,
)

class NotificationsViewModel(private val timelineRepository: TimelineRepository, browsing: BrowsingSession) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState = _uiState.asStateFlow()
    private var notificationsJob: Job? = null
    private var requested = false
    private var hasLoaded = false
    private var nextRefreshResultId = 0L
    private var nextShownNoticeId = 0L
    private var lastSentMarkerId: String? = null
    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        _uiState.value = NotificationsUiState()
        hasLoaded = false
        lastSentMarkerId = null
        if (requested && snapshot.account != null) loadNotifications()
    }

    fun loadNotifications(force: Boolean = false, showPullRefreshIndicator: Boolean = false) {
        requested = true
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isLoadingNotifications || (!force && hasLoaded)) return
        val knownIdsAtRequestStart = _uiState.value.notifications
            .mapTo(mutableSetOf(), TimelineNotification::id)
        val pendingAtRequestStart = _uiState.value.pendingNewNotificationIds
        val initialLoad = !hasLoaded
        notificationsJob?.cancel()
        _uiState.update { it.copy(
            isLoadingNotifications = true,
            isPullRefreshingNotifications = showPullRefreshIndicator,
            isLoadingMoreNotifications = false,
            refreshResult = null,
            notificationsError = null, notificationsErrorIsPagination = false,
        ) }
        notificationsJob = requestScope.launch {
            val markerId = if (initialLoad) {
                timelineRepository.getNotificationMarker(session).forSession(snapshot).getOrNull()
            } else null
            timelineRepository.getNotifications(session)
                .forSession(snapshot).onSuccess { page ->
                    hasLoaded = true
                    val fetchedNewIds = if (initialLoad) {
                        markerId?.let { lastRead ->
                            page.notifications.takeWhile { it.id != lastRead }.mapTo(mutableSetOf(), TimelineNotification::id)
                        }.orEmpty()
                    } else {
                        page.notifications.map(TimelineNotification::id).filterNotTo(mutableSetOf(), knownIdsAtRequestStart::contains)
                    }
                    val refreshResult = NotificationsRefreshResult(
                        id = ++nextRefreshResultId,
                        hasNewNotifications = fetchedNewIds.isNotEmpty() || _uiState.value.pendingNewNotificationIds.isNotEmpty(),
                    )
                    _uiState.update { current ->
                        val streamedDuringRefresh = current.pendingNewNotificationIds - pendingAtRequestStart
                        current.copy(
                            // Keep a streaming event that may have arrived while
                            // this REST refresh was in flight, and retain older
                            // pages already loaded below the refreshed first page.
                            notifications = (page.notifications + current.notifications)
                                .distinctBy(TimelineNotification::id)
                                .sortedByDescending(TimelineNotification::createdAt),
                            isLoadingNotifications = false,
                            isPullRefreshingNotifications = false,
                            isInitialPageLoaded = true,
                            notificationsNextMaxId = page.nextMaxId,
                            notificationsEndReached = page.endReached,
                            pendingNewNotificationIds = current.pendingNewNotificationIds + fetchedNewIds,
                            highlightedNotificationIds = fetchedNewIds + streamedDuringRefresh +
                                (if (initialLoad) pendingAtRequestStart else emptySet()),
                            refreshResult = refreshResult,
                        )
                    }
                }
                .onFailure { error ->
                    val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                        "通知を表示するには、ログアウト後に再ログインして通知の読み取りを許可してください。"
                    } else error.message ?: "通知を取得できませんでした"
                    _uiState.update { it.copy(
                        isLoadingNotifications = false,
                        isPullRefreshingNotifications = false,
                        notificationsError = message,
                    ) }
                }
        }
    }

    /** Refresh whenever the notification tab becomes visible or returns to foreground. */
    fun onNotificationsVisible() = loadNotifications(force = true)

    /** Refresh initiated by the pull-to-refresh gesture. */
    fun refreshNotifications() = loadNotifications(force = true, showPullRefreshIndicator = true)

    fun onNotificationsHidden() {
        _uiState.update { it.copy(highlightedNotificationIds = emptySet(), shownNewNotice = null) }
    }

    /** Called only after the newest row in the selected list is actually laid out at the top. */
    fun onLatestNotificationsShown(shownIds: Set<String>, allNotificationsShown: Boolean) {
        val snapshot = currentSnapshot() ?: return
        if (shownIds.isEmpty()) return
        val state = _uiState.value
        if (!state.isInitialPageLoaded || state.isLoadingNotifications ||
            (state.notificationsError != null && !state.notificationsErrorIsPagination)) return
        val acknowledged = state.pendingNewNotificationIds.intersect(shownIds)
        if (acknowledged.isNotEmpty()) {
            _uiState.update { current -> current.copy(
                pendingNewNotificationIds = current.pendingNewNotificationIds - acknowledged,
                shownNewNotice = ShownNewNotice(++nextShownNoticeId, acknowledged.size),
            ) }
        }
        val latestId = if (allNotificationsShown) state.notifications.firstOrNull()?.id else null
        if (latestId != null && latestId in shownIds && latestId != lastSentMarkerId) {
            lastSentMarkerId = latestId
            requestScope.launch {
                val result = timelineRepository.saveNotificationMarker(snapshot.account!!, latestId).forSession(snapshot)
                if (result.isFailure && lastSentMarkerId == latestId) lastSentMarkerId = null
            }
        }
    }

    fun consumeShownNewNotice(id: Long) {
        _uiState.update { state ->
            if (state.shownNewNotice?.id == id) state.copy(shownNewNotice = null) else state
        }
    }

    fun consumeRefreshResult(id: Long) {
        _uiState.update { state ->
            if (state.refreshResult?.id == id) state.copy(refreshResult = null) else state
        }
    }

    fun loadNextNotifications() {
        val state = _uiState.value
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        val cursor = state.notificationsNextMaxId ?: return
        if (state.isLoadingNotifications || state.isLoadingMoreNotifications || state.notificationsEndReached) return
        notificationsJob = requestScope.launch {
            _uiState.update { it.copy(
                isLoadingMoreNotifications = true, notificationsError = null, notificationsErrorIsPagination = false,
            ) }
            timelineRepository.getNotifications(session, maxId = cursor)
                .forSession(snapshot).onSuccess { page ->
                    _uiState.update { current ->
                        current.copy(
                            notifications = (current.notifications + page.notifications)
                                .distinctBy(TimelineNotification::id),
                            isLoadingMoreNotifications = false,
                            notificationsNextMaxId = page.nextMaxId,
                            notificationsEndReached = page.endReached || page.nextMaxId == current.notificationsNextMaxId,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingMoreNotifications = false,
                            notificationsError = error.message ?: "通知の続きを取得できませんでした",
                            notificationsErrorIsPagination = true,
                        )
                    }
                }
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (change is BrowsingSession.Change.Stream && change.event is TimelineStreamEvent.NotificationReceived) {
            val notification = change.event.notification
            _uiState.update { current ->
                if (current.notifications.any { it.id == notification.id }) current else current.copy(
                    notifications = listOf(notification) + current.notifications,
                    pendingNewNotificationIds = current.pendingNewNotificationIds + notification.id,
                    highlightedNotificationIds = current.highlightedNotificationIds + notification.id,
                )
            }
        } else {
            val deleted = when (change) {
                is BrowsingSession.Change.StatusDeleted -> change.statusId
                is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
                else -> null
            }
            _uiState.update { state -> state.copy(notifications = state.notifications.map { notification ->
                notification.copy(status = notification.status?.let { status ->
                    when {
                        status.statusId == deleted -> null
                        change is BrowsingSession.Change.StatusUpdated -> status.withUpdatedActions(change.status)
                        else -> status
                    }
                })
            }) }
        }
    }
}
