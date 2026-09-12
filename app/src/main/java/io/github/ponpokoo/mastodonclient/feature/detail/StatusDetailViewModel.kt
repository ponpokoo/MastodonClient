package io.github.ponpokoo.mastodonclient.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusDetailUiState(
    val detail: StatusDetail? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val accountListTitle: String? = null,
    val accounts: List<StatusAuthor> = emptyList(),
    val isLoadingAccounts: Boolean = false,
)

class StatusDetailViewModel(
    private val statusId: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatusDetailUiState())
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null

    init { load() }

    fun retry() = load()

    fun showBoosters() = loadAccounts("ブーストしたアカウント") { current ->
        timelineRepository.getRebloggedBy(current, statusId)
    }

    fun showFavourites() = loadAccounts("お気に入りしたアカウント") { current ->
        timelineRepository.getFavouritedBy(current, statusId)
    }

    fun showReaction(reaction: EmojiReaction) =
        loadAccounts("${reaction.name} でリアクションしたアカウント") { current ->
            timelineRepository.getEmojiReactionedBy(current, statusId).map { accounts ->
                if (reaction.accountIds.isEmpty()) accounts
                else accounts.filter { it.id in reaction.accountIds }
            }
        }

    fun toggleFavourite() = mutateStatus { current, status ->
        timelineRepository.setFavourite(current, status.statusId, !status.favourited)
    }

    fun toggleReblog() = mutateStatus { current, status ->
        timelineRepository.setReblogged(current, status.statusId, !status.reblogged)
    }

    fun setReaction(emoji: String?) = mutateStatus { current, status ->
        timelineRepository.setFedibirdReaction(current, status.statusId, emoji)
    }

    fun dismissAccounts() {
        _uiState.update { it.copy(accountListTitle = null, accounts = emptyList()) }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val current = authRepository.restoreSession()
            if (current == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "ログインし直してください") }
                return@launch
            }
            session = current
            timelineRepository.getCachedStatus(current, statusId)?.let { cached ->
                _uiState.update { it.copy(detail = StatusDetail(cached, emptyList(), emptyList())) }
            }
            timelineRepository.getStatusDetail(current, statusId)
                .onSuccess { detail -> _uiState.update { it.copy(detail = detail, isLoading = false) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "投稿を取得できませんでした")
                    }
                }
        }
    }

    private fun loadAccounts(
        title: String,
        request: suspend (AccountSession) -> Result<List<StatusAuthor>>,
    ) {
        val current = session ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(accountListTitle = title, accounts = emptyList(), isLoadingAccounts = true)
            }
            request(current)
                .onSuccess { accounts ->
                    _uiState.update { it.copy(accounts = accounts, isLoadingAccounts = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingAccounts = false, errorMessage = error.message ?: "一覧を取得できませんでした")
                    }
                }
        }
    }

    private fun mutateStatus(
        request: suspend (AccountSession, io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus) ->
            Result<io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus>,
    ) {
        val currentSession = session ?: return
        val currentStatus = _uiState.value.detail?.status ?: return
        viewModelScope.launch {
            request(currentSession, currentStatus)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(detail = state.detail?.copy(status = updated))
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = if (error is retrofit2.HttpException && error.code() in setOf(401, 403)) {
                            "投稿操作には追加権限が必要です。再ログインしてください。"
                        } else error.message ?: "投稿を更新できませんでした")
                    }
                }
        }
    }

    class Factory(
        private val statusId: String,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StatusDetailViewModel(statusId, timelineRepository, authRepository) as T
    }
}
