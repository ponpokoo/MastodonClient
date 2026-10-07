package io.github.ponpokoo.mastodonclient.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.network.InstanceUrlNormalizer
import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.InstanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

enum class LoginOperation { Discover, PrepareAuthorization, CompleteAuthorization, Logout }

data class LoginUiState(
    val instanceInput: String = "",
    val isLoading: Boolean = false,
    val instance: MastodonInstance? = null,
    val session: AccountSession? = null,
    val authorizationUrl: String? = null,
    val errorMessage: String? = null,
    val inputErrorMessage: String? = null,
    val operation: LoginOperation? = null,
)

class LoginViewModel(
    private val instanceRepository: InstanceRepository,
    private val authRepository: AuthRepository,
    private val restoreExistingSession: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        if (restoreExistingSession) {
            viewModelScope.launch {
                authRepository.restoreSession()?.let { session ->
                    _uiState.update { it.copy(session = session) }
                }
            }
        }
    }

    fun onInstanceChanged(value: String) {
        if (_uiState.value.isLoading) return
        _uiState.update {
            it.copy(instanceInput = value, instance = null, authorizationUrl = null,
                errorMessage = null, inputErrorMessage = null)
        }
    }

    fun discover() {
        if (_uiState.value.isLoading) return
        val input = _uiState.value.instanceInput
        if (InstanceUrlNormalizer.normalize(input).isFailure) {
            _uiState.update {
                it.copy(instance = null, authorizationUrl = null, errorMessage = null,
                    inputErrorMessage = "HTTPSのサーバードメインを入力してください。パスや認証情報は含められません。")
            }
            return
        }
        beginOperation(LoginOperation.Discover)
        _uiState.update { it.copy(instance = null, authorizationUrl = null) }
        viewModelScope.launch {
            instanceRepository.discover(input)
                .onSuccess { instance ->
                    ensureActive()
                    _uiState.update { it.copy(isLoading = false, operation = null, instance = instance) }
                }
                .onFailure { error ->
                    ensureActive()
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            operation = null,
                            errorMessage = "サーバーに接続できませんでした。入力内容と通信環境を確認して、もう一度お試しください。",
                        )
                    }
                }
        }
    }

    fun startAuthorization() {
        if (_uiState.value.isLoading || _uiState.value.authorizationUrl != null) return
        val instance = _uiState.value.instance ?: return
        beginOperation(LoginOperation.PrepareAuthorization)
        viewModelScope.launch {
            authRepository.createAuthorizationUrl(instance.baseUrl)
                .onSuccess { url ->
                    ensureActive()
                    _uiState.update { it.copy(isLoading = false, operation = null, authorizationUrl = url) }
                }
                .onFailure { error ->
                    ensureActive()
                    showError(error, "ログインの準備に失敗しました。もう一度お試しください。")
                }
        }
    }

    fun authorizationUrlOpened() {
        _uiState.update { it.copy(authorizationUrl = null) }
    }

    fun authorizationUrlOpenFailed() {
        _uiState.update {
            it.copy(authorizationUrl = null, errorMessage = "ブラウザーを開けませんでした。利用できるブラウザーを確認して、もう一度お試しください。")
        }
    }

    fun completeAuthorization(callbackUrl: String) {
        if (_uiState.value.isLoading) return
        beginOperation(LoginOperation.CompleteAuthorization)
        viewModelScope.launch {
            authRepository.completeAuthorization(callbackUrl)
                .onSuccess { session ->
                    ensureActive()
                    _uiState.update {
                        it.copy(isLoading = false, operation = null, session = session, instance = null)
                    }
                }
                .onFailure { error ->
                    ensureActive()
                    showError(error, "ログインを完了できませんでした。ブラウザーでのログインをもう一度お試しください。")
                }
        }
    }

    fun logout(expected: AccountSession) {
        if (_uiState.value.isLoading) return
        beginOperation(LoginOperation.Logout)
        viewModelScope.launch {
            io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable {
                check(authRepository.logout(expected)) { "削除対象が変更されました。対象を確認してください。" }
                _uiState.value = LoginUiState(session = authRepository.restoreSession())
            }.onFailure { showError(it) }
        }
    }

    private fun beginOperation(operation: LoginOperation) {
        _uiState.update {
            it.copy(isLoading = true, operation = operation, errorMessage = null, inputErrorMessage = null)
        }
    }

    private fun showError(error: Throwable, message: String = error.message ?: "処理に失敗しました") {
        if (error is CancellationException) throw error
        _uiState.update {
            it.copy(
                isLoading = false,
                operation = null,
                authorizationUrl = null,
                errorMessage = message,
            )
        }
    }

    class Factory(
        private val instanceRepository: InstanceRepository,
        private val authRepository: AuthRepository,
        private val restoreExistingSession: Boolean = true,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(instanceRepository, authRepository, restoreExistingSession) as T
    }
}
