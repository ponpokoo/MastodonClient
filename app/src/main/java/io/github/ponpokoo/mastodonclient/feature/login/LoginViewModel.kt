package io.github.ponpokoo.mastodonclient.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.InstanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val instanceInput: String = "",
    val isLoading: Boolean = false,
    val instance: MastodonInstance? = null,
    val session: AccountSession? = null,
    val authorizationUrl: String? = null,
    val errorMessage: String? = null,
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
        _uiState.update { it.copy(instanceInput = value, instance = null, errorMessage = null) }
    }

    fun discover() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, instance = null, errorMessage = null) }
            instanceRepository.discover(_uiState.value.instanceInput)
                .onSuccess { instance -> _uiState.update { it.copy(isLoading = false, instance = instance) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "インスタンスに接続できませんでした",
                        )
                    }
                }
        }
    }

    fun startAuthorization() {
        val instance = _uiState.value.instance ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.createAuthorizationUrl(instance.baseUrl)
                .onSuccess { url ->
                    _uiState.update { it.copy(isLoading = false, authorizationUrl = url) }
                }
                .onFailure(::showError)
        }
    }

    fun authorizationUrlOpened() {
        _uiState.update { it.copy(authorizationUrl = null) }
    }

    fun completeAuthorization(callbackUrl: String) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.completeAuthorization(callbackUrl)
                .onSuccess { session ->
                    _uiState.update {
                        it.copy(isLoading = false, session = session, instance = null)
                    }
                }
                .onFailure(::showError)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = LoginUiState()
        }
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(
                isLoading = false,
                authorizationUrl = null,
                errorMessage = error.message ?: "処理に失敗しました",
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
