package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountProfileUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class AccountProfileViewModel(
    private val accountId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountProfileUiState())
    val uiState: StateFlow<AccountProfileUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null

    init {
        load()
    }

    fun retry() = load()

    fun toggleFavourite(status: TimelineStatus) = mutateStatus(status) { current ->
        timelineRepository.setFavourite(current, status.statusId, !status.favourited)
    }

    fun toggleReblog(status: TimelineStatus) = mutateStatus(status) { current ->
        timelineRepository.setReblogged(current, status.statusId, !status.reblogged)
    }

    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus(status) { current ->
        timelineRepository.setFedibirdReaction(current, status.statusId, emoji)
    }

    private fun load() {
        if (_uiState.value.isLoading && _uiState.value.profile != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val current = authRepository.restoreSession()
            if (current == null) {
                _uiState.value = AccountProfileUiState(isLoading = false, errorMessage = "ログインが必要です")
                return@launch
            }
            session = current
            timelineRepository.getProfile(current, accountId)
                .onSuccess { profile -> _uiState.value = AccountProfileUiState(profile = profile, isLoading = false) }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "プロフィールを取得できませんでした") }
                }
        }
    }

    private fun mutateStatus(
        original: TimelineStatus,
        request: suspend (AccountSession) -> Result<TimelineStatus>,
    ) {
        val current = session ?: return
        viewModelScope.launch {
            request(current)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            profile = state.profile?.copy(
                                statuses = state.profile.statuses.map {
                                    if (it.statusId == original.statusId) updated else it
                                },
                            ),
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = error.message) } }
        }
    }

    class Factory(
        private val accountId: String,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountProfileViewModel(accountId, timelineRepository, authRepository) as T
    }
}
