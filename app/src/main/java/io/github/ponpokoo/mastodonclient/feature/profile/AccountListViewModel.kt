package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
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
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val errorMessage: String? = null,
    val relationships: Map<String, AccountRelationship> = emptyMap(),
    val mutatingAccountIds: Set<String> = emptySet(),
    val relationshipError: String? = null,
    val viewerAccountId: String? = null,
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

    fun retryRelationships() {
        val current = session ?: return
        viewModelScope.launch { loadRelationships(current, _uiState.value.accounts) }
    }

    fun toggleFollow(accountId: String) {
        val current = session ?: return
        val relationship = _uiState.value.relationships[accountId] ?: return
        if (relationship.requested || accountId in _uiState.value.mutatingAccountIds) return
        _uiState.update { it.copy(mutatingAccountIds = it.mutatingAccountIds + accountId, relationshipError = null) }
        viewModelScope.launch {
            timelineRepository.setFollowing(current, accountId, !relationship.following).fold(
                onSuccess = { updated -> _uiState.update { state -> state.copy(
                    relationships = state.relationships + (accountId to updated),
                    mutatingAccountIds = state.mutatingAccountIds - accountId,
                ) } },
                onFailure = { error -> _uiState.update { state -> state.copy(
                    mutatingAccountIds = state.mutatingAccountIds - accountId,
                    relationshipError = error.message ?: "フォロー状態を変更できませんでした",
                ) } },
            )
        }
    }

    private suspend fun loadRelationships(current: AccountSession, accounts: List<StatusAuthor>) {
        val ids = accounts.map(StatusAuthor::id).filterNot { it == current.accountId }
        if (ids.isEmpty()) return
        ids.chunked(40).forEach { batch ->
            timelineRepository.getRelationships(current, batch).fold(
                onSuccess = { relationships -> _uiState.update { state -> state.copy(
                    relationships = state.relationships + relationships, relationshipError = null,
                ) } },
                onFailure = { error -> _uiState.update { it.copy(
                    relationshipError = error.message ?: "フォロー状態を取得できませんでした",
                ) } },
            )
        }
    }

    fun loadMore() {
        val current = session ?: return
        val state = _uiState.value
        val maxId = state.nextMaxId ?: return
        if (state.isLoading || state.isLoadingMore || state.endReached) return
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        viewModelScope.launch {
            timelineRepository.getAccountListPage(current, accountId, followers, maxId).fold(
                onSuccess = { page ->
                    _uiState.update {
                        val merged = (it.accounts + page.accounts).distinctBy(StatusAuthor::id)
                        it.copy(
                            accounts = merged,
                            isLoadingMore = false,
                            nextMaxId = page.nextMaxId,
                            endReached = page.endReached || page.nextMaxId == maxId || merged.size == it.accounts.size,
                        )
                    }
                    loadRelationships(current, page.accounts)
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
        timelineRepository.getAccountListPage(current, accountId, followers).fold(
            onSuccess = { page ->
                _uiState.value = AccountListUiState(accounts = page.accounts, isLoading = false,
                    nextMaxId = page.nextMaxId, endReached = page.endReached,
                    viewerAccountId = current.accountId)
                loadRelationships(current, page.accounts)
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
