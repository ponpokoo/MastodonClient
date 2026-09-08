package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.StreamingPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class TimelineUiState(
    val session: AccountSession? = null,
    val statuses: List<TimelineStatus> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val nextMaxId: String? = null,
    val errorMessage: String? = null,
    val requiresLogin: Boolean = false,
    val announcements: List<ServerAnnouncement> = emptyList(),
    val announcementsVisible: Boolean = false,
    val isLoadingAnnouncements: Boolean = false,
    val announcementsError: String? = null,
    val refreshNewStatusCount: Int? = null,
    val profile: UserProfile? = null,
    val isLoadingProfile: Boolean = false,
    val profileError: String? = null,
    val notifications: List<TimelineNotification> = emptyList(),
    val isLoadingNotifications: Boolean = false,
    val notificationsError: String? = null,
    val searchQuery: String = "",
    val searchResults: SearchResults? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val selectedFeed: TimelineFeed = TimelineFeed.Home,
    val notificationsNextMaxId: String? = null,
    val notificationsEndReached: Boolean = false,
    val isLoadingMoreNotifications: Boolean = false,
    val unreadNotifications: Int = 0,
    val sessions: List<AccountSession> = emptyList(),
    val preferences: AppPreferences = AppPreferences(),
)

class TimelineViewModel(
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val preferencesStore: UserPreferencesStore? = null,
    private val networkIsWifi: () -> Boolean = { true },
) : ViewModel() {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()
    private var streamingJob: Job? = null
    private var isForeground = true

    init {
        preferencesStore?.let { store ->
            viewModelScope.launch {
                store.preferences.collect { preferences ->
                    val old = _uiState.value.preferences
                    _uiState.update { it.copy(preferences = preferences) }
                    val session = _uiState.value.session
                    if (session != null && old.forAccount(session.sessionId).streaming !=
                        preferences.forAccount(session.sessionId).streaming
                    ) startStreaming(session)
                }
            }
        }
        loadInitial()
    }

    fun switchAccount(sessionId: String) {
        if (_uiState.value.session?.sessionId == sessionId) return
        viewModelScope.launch {
            val session = authRepository.switchSession(sessionId) ?: return@launch
            streamingJob?.cancel()
            _uiState.value = TimelineUiState(
                session = session,
                sessions = authRepository.getSessions(),
                preferences = _uiState.value.preferences,
            )
            loadForSession(session)
        }
    }

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        val session = _uiState.value.session ?: return
        if (!foreground && _uiState.value.preferences.pauseStreamingInBackground) {
            streamingJob?.cancel()
            streamingJob = null
        } else if (foreground) {
            startStreaming(session)
        }
    }

    fun refresh() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isRefreshing || _uiState.value.isInitialLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            timelineRepository.getTimeline(session, _uiState.value.selectedFeed)
                .onSuccess { page ->
                    val existingIds = _uiState.value.statuses.mapTo(mutableSetOf(), TimelineStatus::timelineId)
                    val newCount = page.statuses.count { it.timelineId !in existingIds }
                    _uiState.update {
                        it.copy(
                            statuses = page.statuses,
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
        val session = state.session ?: return
        val cursor = state.nextMaxId ?: return
        if (state.isInitialLoading || state.isRefreshing || state.isLoadingMore || state.endReached) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            timelineRepository.getTimeline(session, state.selectedFeed, maxId = cursor)
                .onSuccess { page ->
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
        val session = _uiState.value.session ?: return
        if (feed == _uiState.value.selectedFeed || _uiState.value.isInitialLoading) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedFeed = feed,
                    statuses = emptyList(),
                    isInitialLoading = true,
                    isLoadingMore = false,
                    endReached = false,
                    nextMaxId = null,
                    errorMessage = null,
                )
            }
            timelineRepository.getTimeline(session, feed)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            statuses = page.statuses,
                            isInitialLoading = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, initial = true) }
        }
    }

    fun toggleFavourite(status: TimelineStatus) = mutateStatus {
        timelineRepository.setFavourite(it, status.statusId, !status.favourited)
    }

    fun toggleReblog(status: TimelineStatus) = mutateStatus {
        timelineRepository.setReblogged(it, status.statusId, !status.reblogged)
    }

    fun toggleBookmark(status: TimelineStatus) = mutateStatus {
        timelineRepository.setBookmarked(it, status.statusId, !status.bookmarked)
    }

    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus {
        timelineRepository.setFedibirdReaction(it, status.statusId, emoji)
    }

    fun showAnnouncements() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isLoadingAnnouncements) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    announcementsVisible = true,
                    isLoadingAnnouncements = true,
                    announcementsError = null,
                )
            }
            timelineRepository.getAnnouncements(session)
                .onSuccess { announcements ->
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

    fun consumeRefreshResult() {
        _uiState.update { it.copy(refreshNewStatusCount = null) }
    }

    fun loadProfile() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isLoadingProfile || _uiState.value.profile != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProfile = true, profileError = null) }
            timelineRepository.getProfile(session)
                .onSuccess { profile ->
                    _uiState.update { it.copy(profile = profile, isLoadingProfile = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProfile = false, profileError = error.message ?: "プロフィールを取得できませんでした")
                    }
                }
        }
    }

    fun loadNotifications(force: Boolean = false) {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isLoadingNotifications || (!force && _uiState.value.notifications.isNotEmpty())) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingNotifications = true, notificationsError = null) }
            timelineRepository.getNotifications(session)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            notifications = page.notifications,
                            isLoadingNotifications = false,
                            notificationsNextMaxId = page.nextMaxId,
                            notificationsEndReached = page.endReached,
                            unreadNotifications = 0,
                        )
                    }
                    page.notifications.firstOrNull()?.id?.let { latestId ->
                        timelineRepository.saveNotificationMarker(session, latestId)
                    }
                }
                .onFailure { error ->
                    val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                        "通知を表示するには、ログアウト後に再ログインして通知の読み取りを許可してください。"
                    } else error.message ?: "通知を取得できませんでした"
                    _uiState.update { it.copy(isLoadingNotifications = false, notificationsError = message) }
                }
        }
    }

    fun loadNextNotifications() {
        val state = _uiState.value
        val session = state.session ?: return
        val cursor = state.notificationsNextMaxId ?: return
        if (state.isLoadingNotifications || state.isLoadingMoreNotifications || state.notificationsEndReached) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreNotifications = true, notificationsError = null) }
            timelineRepository.getNotifications(session, maxId = cursor)
                .onSuccess { page ->
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
                        )
                    }
                }
        }
    }

    fun onSearchQueryChanged(value: String) {
        _uiState.update { it.copy(searchQuery = value, searchError = null) }
    }

    fun search() {
        val session = _uiState.value.session ?: return
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty() || _uiState.value.isSearching) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchError = null) }
            timelineRepository.search(session, query)
                .onSuccess { results ->
                    _uiState.update { it.copy(searchResults = results, isSearching = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSearching = false, searchError = error.message ?: "検索できませんでした") }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            val next = authRepository.restoreSession()
            if (next == null) {
                _uiState.value = TimelineUiState(isInitialLoading = false, requiresLogin = true)
            } else {
                _uiState.value = TimelineUiState(
                    session = next,
                    sessions = authRepository.getSessions(),
                    preferences = _uiState.value.preferences,
                )
                loadForSession(next)
            }
        }
    }

    private fun mutateStatus(
        request: suspend (AccountSession) -> Result<TimelineStatus>,
    ) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            request(session)
                .onSuccess { updated ->
                    _uiState.update { current ->
                        current.copy(
                            statuses = current.statuses.map { existing ->
                                if (existing.statusId != updated.statusId) existing
                                else existing.copy(
                                    repliesCount = updated.repliesCount,
                                    boostsCount = updated.boostsCount,
                                    favouritesCount = updated.favouritesCount,
                                    favourited = updated.favourited,
                                    reblogged = updated.reblogged,
                                    bookmarked = updated.bookmarked,
                                    reactions = updated.reactions,
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                        "投稿操作には追加権限が必要です。設定からログアウト後、再ログインしてください。"
                    } else error.message ?: "投稿を更新できませんでした"
                    _uiState.update { it.copy(errorMessage = message) }
                }
        }
    }

    private fun loadInitial() {
        if (_uiState.value.isInitialLoading && _uiState.value.session != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isInitialLoading = true, errorMessage = null, requiresLogin = false)
            }
            val sessions = authRepository.getSessions()
            val session = authRepository.restoreSession()
            if (session == null) {
                _uiState.value = TimelineUiState(isInitialLoading = false, requiresLogin = true)
                return@launch
            }
            _uiState.update { it.copy(session = session, sessions = sessions) }
            loadForSession(session)
        }
    }

    private suspend fun loadForSession(session: AccountSession) {
        startStreaming(session)
        timelineRepository.getTimeline(session, TimelineFeed.Home)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            statuses = page.statuses,
                            isInitialLoading = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, initial = true) }
    }

    private fun startStreaming(session: AccountSession) {
        streamingJob?.cancel()
        if (!isForeground && _uiState.value.preferences.pauseStreamingInBackground) return
        when (_uiState.value.preferences.forAccount(session.sessionId).streaming) {
            StreamingPolicy.Off -> return
            StreamingPolicy.WifiOnly -> if (!networkIsWifi()) return
            StreamingPolicy.On -> Unit
        }
        streamingJob = viewModelScope.launch {
            timelineRepository.observeUserStream(session)
                .retryWhen { _, attempt ->
                    delay((2_000L * (attempt + 1)).coerceAtMost(30_000L))
                    true
                }
                .collect { event ->
                    _uiState.update { current ->
                        when (event) {
                            is TimelineStreamEvent.StatusAdded -> current.copy(
                                statuses = if (current.selectedFeed == TimelineFeed.Home) {
                                    (listOf(event.status) + current.statuses).distinctBy(TimelineStatus::timelineId)
                                } else current.statuses,
                            )
                            is TimelineStreamEvent.StatusDeleted -> current.copy(
                                statuses = current.statuses.filterNot {
                                    it.statusId == event.statusId || it.timelineId == event.statusId
                                },
                            )
                            is TimelineStreamEvent.NotificationReceived -> current.copy(
                                notifications = (listOf(event.notification) + current.notifications)
                                    .distinctBy(TimelineNotification::id),
                                unreadNotifications = current.unreadNotifications + 1,
                            )
                        }
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

    class Factory(
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
        private val preferencesStore: UserPreferencesStore? = null,
        private val networkIsWifi: () -> Boolean = { true },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimelineViewModel(timelineRepository, authRepository, preferencesStore, networkIsWifi) as T
    }
}
