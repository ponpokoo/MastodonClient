package io.github.ponpokoo.mastodonclient.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.StreamingPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainSessionUiState(
    val session: AccountSession? = null,
    val sessions: List<AccountSession> = emptyList(),
    val preferences: AppPreferences = AppPreferences(),
    val preferencesLoaded: Boolean = false,
    val requiresLogin: Boolean = false,
    val errorMessage: String? = null,
    val changingAccountOrder: Boolean = false,
    val accountOrderError: String? = null,
)

/** Owns account selection and the single streaming connection, never tab content. */
class MainSessionViewModel(
    private val authRepository: AuthRepository,
    private val timelineRepository: TimelineRepository,
    preferencesStore: UserPreferencesStore? = null,
    private val networkIsWifi: () -> Boolean = { true },
    private val systemNotifications: io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository? = null,
    private val pushSync: io.github.ponpokoo.mastodonclient.domain.repository.PushSyncRepository? = null,
) : ViewModel() {
    val browsing = BrowsingSession()
    private val mutableState = MutableStateFlow(MainSessionUiState(preferencesLoaded = preferencesStore == null))
    val uiState = mutableState.asStateFlow()
    private var restoreJob: Job? = null
    private var accountChangeJob: Job? = null
    private var streamingJob: Job? = null
    private var displayRefreshJob: Job? = null
    private var settingsJob: Job? = null
    private var filterSyncJob: Job? = null
    private var filtersDirty = false
    private var streamingSnapshot: BrowsingSession.Snapshot? = null
    private var preferencesReady = preferencesStore == null
    @Volatile private var isForeground = true
    private var foregroundLifecycle: androidx.lifecycle.Lifecycle? = null
    private val foregroundObserver = androidx.lifecycle.LifecycleEventObserver { _, _ ->
        setForeground(foregroundLifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true)
    }

    fun bindForegroundLifecycle(lifecycle: androidx.lifecycle.Lifecycle) {
        if (foregroundLifecycle === lifecycle) return
        foregroundLifecycle?.removeObserver(foregroundObserver)
        foregroundLifecycle = lifecycle
        setForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        lifecycle.addObserver(foregroundObserver)
    }

    override fun onCleared() {
        foregroundLifecycle?.removeObserver(foregroundObserver)
        foregroundLifecycle = null
    }

    init {
        recoverPush()
        viewModelScope.launch {
            timelineRepository.observeNetworkChanges().collect { startStreaming() }
        }
        viewModelScope.launch {
            var previous = emptyMap<String, List<io.github.ponpokoo.mastodonclient.domain.model.ServerFilter>?>()
            timelineRepository.serverSettings.collect { settings ->
                val snapshot = browsing.snapshot.value
                val account = snapshot.account
                if (account != null && settings[account.sessionId]?.belongsTo(account) == true && previous.containsKey(account.sessionId) && settings[account.sessionId]?.filters != null && previous[account.sessionId] != settings[account.sessionId]?.filters) {
                    browsing.publish(snapshot, BrowsingSession.Change.Stream(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.FiltersChanged))
                }
                previous = settings.mapValues { it.value.filters }
            }
        }
        viewModelScope.launch {
            authRepository.observeSessions().collect { sessions ->
                val current = mutableState.value.session
                val updated = sessions.firstOrNull { it.sessionId == current?.sessionId }
                if (current != null && updated != null && !current.hasSameCredentials(updated)) {
                    activate(updated, sessions)
                    return@collect
                }
                mutableState.update { state ->
                    val current = state.session
                    val updated = sessions.firstOrNull { it.sessionId == current?.sessionId }
                    state.copy(sessions = sessions,
                        session = if (current?.hasSameCredentials(updated) == true) updated else current)
                }
            }
        }
        preferencesStore?.let { store ->
            viewModelScope.launch {
                store.preferences.collect { preferences ->
                    val firstPreferences = !preferencesReady
                    preferencesReady = true
                    val previous = mutableState.value.preferences
                    mutableState.update { it.copy(preferences = preferences, preferencesLoaded = true) }
                    val sessionId = mutableState.value.session?.sessionId
                    if (firstPreferences || previous.forAccount(sessionId).streaming != preferences.forAccount(sessionId).streaming ||
                        previous.pauseStreamingInBackground != preferences.pauseStreamingInBackground || previous.foregroundNotificationsEnabled != preferences.foregroundNotificationsEnabled
                    ) startStreaming()
                }
            }
        }
        restore()
    }

    fun restore() {
        if (accountChangeJob?.isActive == true) return
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            val before = browsing.snapshot.value
            runCatchingCancellable {
                val sessions = authRepository.getSessions()
                val account = authRepository.restoreSession()
                currentCoroutineContext().ensureActive()
                if (browsing.snapshot.value == before) activate(account, sessions)
            }.onFailure(::showError)
        }
    }

    fun switchAccount(sessionId: String) {
        if (uiState.value.session?.sessionId == sessionId || accountChangeJob?.isActive == true) return
        restoreJob?.cancel()
        displayRefreshJob?.cancel()
        accountChangeJob = viewModelScope.launch {
            runCatchingCancellable {
                val account = authRepository.switchSession(sessionId) ?: return@runCatchingCancellable
                val sessions = authRepository.getSessions()
                activate(account, sessions)
            }.onFailure(::showError)
        }
    }

    /** Wait for startup or another account change before opening a notification for this account. */
    suspend fun switchAccountAndWait(sessionId: String): Boolean {
        restoreJob?.cancelAndJoin()
        accountChangeJob?.join()
        switchAccount(sessionId)
        accountChangeJob?.join()
        return uiState.value.session?.sessionId == sessionId
    }

    suspend fun dismissSystemNotificationsForActiveAccount(sessionId: String) {
        if (uiState.value.session?.sessionId != sessionId) return
        runCatchingCancellable { systemNotifications?.dismissForAccount(sessionId) }
    }

    fun logout() {
        if (accountChangeJob?.isActive == true) return
        restoreJob?.cancel()
        displayRefreshJob?.cancel()
        streamingJob?.cancel()
        settingsJob?.cancel()
        filterSyncJob?.cancel()
        browsing.activate(null)
        accountChangeJob = viewModelScope.launch {
            runCatchingCancellable {
                authRepository.logout()
                val account = authRepository.restoreSession()
                val sessions = authRepository.getSessions()
                activate(account, sessions)
            }.onFailure {
                browsing.activate(uiState.value.session)
                startStreaming()
                showError(it)
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        if (foreground) recoverPush()
        startStreaming()
    }

    private fun recoverPush() {
        viewModelScope.launch { runCatchingCancellable { pushSync?.recover(null, false) } }
    }

    private fun activate(account: AccountSession?, sessions: List<AccountSession>) {
        if (account != null && account.hasSameCredentials(browsing.snapshot.value.account)) {
            mutableState.update { it.copy(session = account, sessions = sessions, requiresLogin = false, errorMessage = null) }
            refreshAccountDisplay(account)
            return
        }
        streamingJob?.cancel()
        streamingSnapshot = null
        settingsJob?.cancel()
        filterSyncJob?.cancel()
        displayRefreshJob?.cancel()
        browsing.activate(account)
        val snapshot = browsing.snapshot.value
        mutableState.update { it.copy(session = account, sessions = sessions, requiresLogin = account == null, errorMessage = null) }
        if (account != null) settingsJob = viewModelScope.launch {
            runCatchingCancellable { timelineRepository.activateServerSettings(account); timelineRepository.prepareServerSettings(account) }
            currentCoroutineContext().ensureActive()
            if (browsing.snapshot.value == snapshot) timelineRepository.syncServerSettings(account)
        }
        startStreaming()
        refreshAccountDisplay(account)
    }

    fun moveAccount(sessionId: String, beforeSessionId: String?) {
        if (uiState.value.changingAccountOrder) return
        mutableState.update { it.copy(changingAccountOrder = true, accountOrderError = null) }
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    authRepository.moveAccount(sessionId, beforeSessionId).getOrThrow()
                    val sessions = authRepository.getSessions()
                    mutableState.update { it.copy(sessions = sessions) }
                }.onFailure {
                    mutableState.update { it.copy(accountOrderError = "並び順を保存できませんでした。再試行してください。") }
                }
            } finally {
                mutableState.update { it.copy(changingAccountOrder = false) }
            }
        }
    }

    private fun refreshAccountDisplay(account: AccountSession?) {
        displayRefreshJob?.cancel()
        if (account == null) return
        displayRefreshJob = viewModelScope.launch {
            // Saved information is already visible. Failure leaves it usable, including offline.
            runCatchingCancellable { authRepository.refreshAccountDisplay(account) }
        }
    }

    private fun streamingAllowed(account: AccountSession): Boolean {
        val preferences = uiState.value.preferences
        return preferencesReady && (isForeground || !preferences.pauseStreamingInBackground) && when (preferences.forAccount(account.sessionId).streaming) {
            StreamingPolicy.Off -> false
            StreamingPolicy.WifiOnly -> networkIsWifi()
            StreamingPolicy.On -> true
        }
    }

    private fun startStreaming() {
        val snapshot = browsing.snapshot.value
        val account = snapshot.account ?: return
        if (!streamingAllowed(account)) { streamingJob?.cancel(); streamingSnapshot = null; return }
        if (streamingJob?.isActive == true && streamingSnapshot == snapshot) return
        streamingJob?.cancel()
        streamingSnapshot = snapshot
        streamingJob = viewModelScope.launch {
            timelineRepository.observeUserStream(account)
                .retryWhen { _, attempt ->
                    delay((2_000L * (attempt + 1)).coerceAtMost(30_000L))
                    browsing.snapshot.value == snapshot && streamingAllowed(account)
                }.catch { error ->
                    // retryWhen(false) rethrows the last connection error. A policy stop
                    // is expected; a subsequent network event can start a new connection.
                    if (browsing.snapshot.value == snapshot && streamingAllowed(account)) throw error
                }.collect { event ->
                    currentCoroutineContext().ensureActive()
                    if (event is io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.FiltersChanged ||
                        event is io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.Connected) {
                        if (event is io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.Connected)
                            browsing.publish(snapshot, BrowsingSession.Change.Stream(event))
                        if (browsing.snapshot.value == snapshot) requestFilterSync(snapshot)
                        return@collect
                    }
                    browsing.publish(snapshot, BrowsingSession.Change.Stream(event))
                    if (event is io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.NotificationReceived &&
                        isForeground && uiState.value.preferences.foregroundNotificationsEnabled && browsing.snapshot.value == snapshot
                    ) {
                        viewModelScope.launch {
                            // Delivery must never interrupt the stream or publish a stale account's notification.
                            runCatchingCancellable {
                                systemNotifications?.show(account, event.notification) {
                                    browsing.snapshot.value == snapshot && uiState.value.preferences.foregroundNotificationsEnabled
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun showError(error: Throwable) {
        mutableState.update { it.copy(errorMessage = error.message ?: "アカウントを読み込めませんでした") }
    }

    private fun requestFilterSync(snapshot: BrowsingSession.Snapshot) {
        filtersDirty = true
        if (filterSyncJob?.isActive == true) return
        filterSyncJob = viewModelScope.launch {
            val account = snapshot.account ?: return@launch
            while (filtersDirty && browsing.snapshot.value == snapshot) {
                delay(200)
                filtersDirty = false
                val before = timelineRepository.serverSettings.value[account.sessionId]?.filters
                // Await an earlier startup fetch, then fetch after connection/event receipt to recover missed changes.
                settingsJob?.join()
                timelineRepository.syncServerSettings(account, setOf(io.github.ponpokoo.mastodonclient.domain.model.ServerSetting.Filters))
                ensureActive()
                val after = timelineRepository.serverSettings.value[account.sessionId]
                if (after?.filters == before && after?.displayPending == true) browsing.publish(snapshot,
                    BrowsingSession.Change.Stream(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.FiltersChanged))
            }
        }
    }
}
