package io.github.ponpokoo.mastodonclient.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
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
    val accountListError: String? = null,
    val currentAccountId: String? = null,
    val lists: List<MastodonList> = emptyList(),
    val isLoadingLists: Boolean = false,
    val actionMessage: String? = null,
    val isDeleted: Boolean = false,
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
    fun refresh() {
        if (!_uiState.value.isLoading) load()
    }

    fun showBoosters() {
        if ((_uiState.value.detail?.status?.boostsCount ?: 0L) <= 0L) return
        loadAccounts("ブーストしたアカウント") { current ->
            timelineRepository.getRebloggedBy(current, statusId)
        }
    }

    fun showFavourites() {
        if ((_uiState.value.detail?.status?.favouritesCount ?: 0L) <= 0L) return
        loadAccounts("お気に入りしたアカウント") { current ->
            timelineRepository.getFavouritedBy(current, statusId)
        }
    }

    fun showReaction(reaction: EmojiReaction) =
        loadAccounts(
            if (reaction.accountIds.isEmpty()) "リアクションした人"
            else "${reaction.name} を付けた人",
        ) { current ->
            timelineRepository.getEmojiReactionedBy(current, statusId, reaction.name).map { accounts ->
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
        _uiState.update { it.copy(accountListTitle = null, accounts = emptyList(), accountListError = null) }
    }

    fun consumeActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    fun setPinned(status: TimelineStatus) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.setPinned(current, status.statusId, !status.pinned).fold(
                onSuccess = { updated -> _uiState.update { state -> state.copy(
                    detail = state.detail?.copy(status = updated),
                    actionMessage = if (updated.pinned) "プロフィールに固定しました" else "固定を解除しました",
                ) } },
                onFailure = { showActionError(it, "固定を変更できませんでした") },
            )
        }
    }

    fun deleteStatus(status: TimelineStatus) {
        val current = session ?: return
        viewModelScope.launch {
            timelineRepository.deleteStatus(current, status.statusId).fold(
                onSuccess = { _uiState.update { it.copy(isDeleted = true) } },
                onFailure = { showActionError(it, "投稿を削除できませんでした") },
            )
        }
    }

    fun unfollow(status: TimelineStatus) = runAccountAction("フォローを解除しました") {
        timelineRepository.setFollowing(it, status.author.id, false)
    }

    fun mute(status: TimelineStatus) = runAccountAction("ミュートしました") {
        timelineRepository.setMuted(it, status.author.id, true)
    }

    fun block(status: TimelineStatus) = runAccountAction("ブロックしました") {
        timelineRepository.setBlocked(it, status.author.id, true)
    }

    fun report(status: TimelineStatus, comment: String) = runAccountAction("通報を送信しました") {
        timelineRepository.reportStatus(it, status.author.id, status.statusId, comment)
    }

    fun loadLists() {
        val current = session ?: return
        if (_uiState.value.isLoadingLists) return
        _uiState.update { it.copy(isLoadingLists = true) }
        viewModelScope.launch {
            timelineRepository.getLists(current).fold(
                onSuccess = { lists -> _uiState.update { it.copy(lists = lists, isLoadingLists = false) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingLists = false) }
                    showActionError(error, "リストを取得できませんでした")
                },
            )
        }
    }

    fun addToList(status: TimelineStatus, listId: String) = runAccountAction("リストに追加しました") {
        timelineRepository.addAccountToList(it, listId, status.author.id)
    }

    private fun runAccountAction(message: String, request: suspend (AccountSession) -> Result<*>) {
        val current = session ?: return
        viewModelScope.launch {
            request(current).fold(
                onSuccess = { _uiState.update { it.copy(actionMessage = message) } },
                onFailure = { showActionError(it, "操作に失敗しました") },
            )
        }
    }

    private fun showActionError(error: Throwable, fallback: String) {
        _uiState.update { it.copy(actionMessage = error.message ?: fallback) }
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
            _uiState.update { it.copy(currentAccountId = current.accountId) }
            if (_uiState.value.detail == null) {
                timelineRepository.getCachedStatus(current, statusId)?.let { cached ->
                    _uiState.update { it.copy(detail = StatusDetail(cached, emptyList(), emptyList())) }
                }
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
                it.copy(accountListTitle = title, accounts = emptyList(), isLoadingAccounts = true, accountListError = null)
            }
            request(current)
                .onSuccess { accounts ->
                    _uiState.update { if (it.accountListTitle == title) it.copy(accounts = accounts, isLoadingAccounts = false) else it }
                }
                .onFailure { error ->
                    _uiState.update {
                        if (it.accountListTitle == title) it.copy(
                            isLoadingAccounts = false,
                            accountListError = error.message?.takeIf { message -> message.length <= 100 }
                                ?: "一覧を取得できませんでした",
                        ) else it
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
