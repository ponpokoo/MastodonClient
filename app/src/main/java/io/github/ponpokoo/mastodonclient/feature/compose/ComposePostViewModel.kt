package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class ComposePostUiState(
    val text: String = "",
    val isPosting: Boolean = false,
    val posted: Boolean = false,
    val errorMessage: String? = null,
)

class ComposePostViewModel(
    private val replyToId: String?,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ComposePostUiState())
    val uiState: StateFlow<ComposePostUiState> = _uiState.asStateFlow()
    private var idempotencyKey = UUID.randomUUID().toString()

    fun onTextChanged(text: String) {
        if (text.length <= 500) _uiState.update { it.copy(text = text, errorMessage = null) }
    }

    fun post() {
        val state = _uiState.value
        if (state.text.isBlank() || state.isPosting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            val session = authRepository.restoreSession()
            if (session == null) {
                _uiState.update { it.copy(isPosting = false, errorMessage = "ログインし直してください") }
                return@launch
            }
            timelineRepository.createStatus(session, state.text, replyToId, idempotencyKey)
                .onSuccess {
                    idempotencyKey = UUID.randomUUID().toString()
                    _uiState.update { it.copy(isPosting = false, posted = true) }
                }
                .onFailure { error ->
                    val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                        "投稿には追加権限が必要です。ログアウト後、再ログインしてください。"
                    } else error.message ?: "投稿できませんでした"
                    _uiState.update { it.copy(isPosting = false, errorMessage = message) }
                }
        }
    }

    class Factory(
        private val replyToId: String?,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ComposePostViewModel(replyToId, timelineRepository, authRepository) as T
    }
}
