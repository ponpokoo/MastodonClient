package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PushSettingsViewModel(private val push: PushControlRepository, private val auth: AuthRepository) : ViewModel() {
    val states = push.states
    private val mutableUrl = MutableStateFlow<String?>(null)
    val authorizationUrl = mutableUrl.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableAuthenticated = MutableStateFlow(0)
    val authenticated = mutableAuthenticated.asStateFlow()
    var requiresLogin: Boolean = false
        private set
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var monitor: Job? = null

    fun setForeground(foreground: Boolean) {
        monitor?.cancel()
        monitor = if (foreground) viewModelScope.launch {
            refreshNow()
            while (true) {
                delay(30_000)
                if (states.value.values.any { it.status in setOf(PushControlStatus.ERROR, PushControlStatus.REMOVING, PushControlStatus.REGISTERING) }) refreshNow()
            }
        } else null
    }
    fun retry() { viewModelScope.launch { refreshNow() } }
    private suspend fun refreshNow() {
        runCatchingCancellable { push.refresh() }.onSuccess { mutableError.value = null }
            .onFailure { mutableError.value = "通知設定を読み込めませんでした。再試行してください。" }
    }
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            mutableError.value = null
            runCatchingCancellable { push.setEnabled(id, enabled) }.onFailure { mutableError.value = "通知設定を変更できませんでした。解除待ちの場合は再試行してください。" }
        }
    }
    fun authorize(id: String) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                mutableError.value = null
                auth.createPushAuthorizationUrl(id).onSuccess { mutableUrl.value = it }
                    .onFailure { mutableError.value = "再認証を開始できませんでした。通知設定から再試行してください。" }
            } finally { mutableBusy.value = false }
        }
    }
    fun urlOpened() { mutableUrl.value = null }
    fun navigationHandled() { mutableAuthenticated.value = 0 }
    fun browserFailed() { mutableUrl.value = null; mutableError.value = "認証用ブラウザーを開けませんでした。再試行してください。" }
    fun notificationPermissionDenied() { mutableError.value = "端末の通知許可が必要です。Androidのアプリ設定で通知を許可してください。" }
    suspend fun handlesCallback() = auth.pendingPushAuthorization()
    fun complete(callback: String) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                auth.completeAuthorization(callback).onSuccess { requiresLogin = false; mutableAuthenticated.value++ }
                    .onFailure { mutableError.value = "再認証を完了できませんでした。元のアカウントで再試行してください。" }
                runCatchingCancellable { push.refresh() }
            } finally { mutableBusy.value = false }
        }
    }
    fun logout() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    auth.logout()
                    requiresLogin = auth.restoreSession() == null
                    mutableAuthenticated.value++
                }.onFailure { mutableError.value = "ログアウトを完了できませんでした。再試行してください。" }
            } finally { mutableBusy.value = false }
        }
    }
}
