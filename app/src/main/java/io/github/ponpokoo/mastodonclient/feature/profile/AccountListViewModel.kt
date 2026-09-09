package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountListUiState(
    val accounts: List<StatusAuthor> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val errorMessage: String? = null,
)

class AccountListViewModel(
    private val accountId: String,
    private val followers: Boolean,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountListUiState())
    val uiState: StateFlow<AccountListUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null

    init { load() }

    fun retry() = load()

    fun loadMore() {
        val current = session ?: return
        val state = _uiState.value
        val maxId = state.accounts.lastOrNull()?.id ?: return
        if (state.isLoading || state.isLoadingMore || state.endReached) return
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        viewModelScope.launch {
            timelineRepository.getAccountList(current, accountId, followers, maxId).fold(
                onSuccess = { accounts ->
                    _uiState.update {
                        it.copy(
                            accounts = (it.accounts + accounts).distinctBy(StatusAuthor::id),
                            isLoadingMore = false,
                            endReached = accounts.isEmpty(),
                        )
                    }
                },
                onFailure = ::showError,
            )
        }
    }

    private fun load() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val current = authRepository.restoreSession() ?: run {
            _uiState.value = AccountListUiState(isLoading = false, errorMessage = "ログインが必要です")
            return@launch
        }
        session = current
        timelineRepository.getAccountList(current, accountId, followers).fold(
            onSuccess = { accounts ->
                _uiState.value = AccountListUiState(accounts = accounts, isLoading = false, endReached = accounts.isEmpty())
            },
            onFailure = ::showError,
        )
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(isLoading = false, isLoadingMore = false, errorMessage = error.message ?: "一覧を取得できませんでした")
        }
    }

    class Factory(
        private val accountId: String,
        private val followers: Boolean,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountListViewModel(accountId, followers, timelineRepository, authRepository) as T
    }
}
