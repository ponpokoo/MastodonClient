package io.github.ponpokoo.mastodonclient.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.StreamingPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
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
)

/** Owns account selection and the single streaming connection, never tab content. */
class MainSessionViewModel(
    private val authRepository: AuthRepository,
    private val timelineRepository: TimelineRepository,
    preferencesStore: UserPreferencesStore? = null,
    private val networkIsWifi: () -> Boolean = { true },
    private val systemNotifications: io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository? = null,
) : ViewModel() {
    val browsing = BrowsingSession()
    private val mutableState = MutableStateFlow(MainSessionUiState(preferencesLoaded = preferencesStore == null))
    val uiState = mutableState.asStateFlow()
    private var restoreJob: Job? = null
    private var accountChangeJob: Job? = null
    private var streamingJob: Job? = null
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
        super.onCleared()
    }

    init {
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
            runCatchingCancellable {
                val sessions = authRepository.getSessions()
                val account = authRepository.restoreSession()
                currentCoroutineContext().ensureActive()
                activate(account, sessions)
            }.onFailure(::showError)
        }
    }

    fun switchAccount(sessionId: String) {
        if (uiState.value.session?.sessionId == sessionId || accountChangeJob?.isActive == true) return
        restoreJob?.cancel()
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

    fun logout() {
        if (accountChangeJob?.isActive == true) return
        restoreJob?.cancel()
        streamingJob?.cancel()
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
        startStreaming()
    }

    private fun activate(account: AccountSession?, sessions: List<AccountSession>) {
        streamingJob?.cancel()
        browsing.activate(account)
        mutableState.update { it.copy(session = account, sessions = sessions, requiresLogin = account == null, errorMessage = null) }
        startStreaming()
    }

    private fun startStreaming() {
        streamingJob?.cancel()
        if (!preferencesReady) return
        val snapshot = browsing.snapshot.value
        val account = snapshot.account ?: return
        val preferences = uiState.value.preferences
        if (!isForeground && preferences.pauseStreamingInBackground) return
        when (preferences.forAccount(account.sessionId).streaming) {
            StreamingPolicy.Off -> return
            StreamingPolicy.WifiOnly -> if (!networkIsWifi()) return
            StreamingPolicy.On -> Unit
        }
        streamingJob = viewModelScope.launch {
            timelineRepository.observeUserStream(account)
                .retryWhen { _, attempt ->
                    delay((2_000L * (attempt + 1)).coerceAtMost(30_000L))
                    true
                }.collect { event ->
                    currentCoroutineContext().ensureActive()
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
}
