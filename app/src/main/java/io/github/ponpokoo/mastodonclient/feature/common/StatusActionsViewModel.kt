package io.github.ponpokoo.mastodonclient.feature.common

import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class StatusActionsUiState(
    val lists: List<MastodonList> = emptyList(),
    val isLoadingLists: Boolean = false,
    val actionMessage: String? = null,
)

/** Common actions for all main tabs; results are broadcast to each tab's own state. */
class StatusActionsViewModel(
    private val timelineRepository: TimelineRepository,
    browsing: BrowsingSession,
    private val statusActionManager: StatusActionManager = StatusActionManager(timelineRepository),
) : SessionScopedViewModel(browsing) {
    private val _uiState = MutableStateFlow(StatusActionsUiState())
    val uiState = _uiState.asStateFlow()
    init {
        observeSession()
        viewModelScope.launch {
            statusActionManager.updates.collect { update ->
                val snapshot = currentSnapshot() ?: return@collect
                val account = snapshot.account ?: return@collect
                if (update.sessionId == account.sessionId && update.instanceUrl == account.instanceUrl) {
                    browsing.publish(snapshot, BrowsingSession.Change.StatusUpdated(update.status))
                }
            }
        }
    }
    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) { _uiState.value = StatusActionsUiState() }

    fun toggleFavourite(status: TimelineStatus) =
        runOptimisticAction(status, statusActionManager::beginFavourite)

    fun toggleReblog(status: TimelineStatus) =
        runOptimisticAction(status, statusActionManager::beginReblog)

    fun toggleBookmark(status: TimelineStatus) = mutateStatus {
        timelineRepository.setBookmarked(it, status.statusId, !status.bookmarked)
    }

    fun setReaction(status: TimelineStatus, emoji: String?) = mutateStatus {
        timelineRepository.setFedibirdReaction(it, status.statusId, emoji)
    }

    fun votePoll(status: TimelineStatus, choices: Set<Int>) {
        val poll = status.poll ?: return
        mutateStatus(successMessage = "投票しました") {
            timelineRepository.votePoll(it, status.statusId, poll.id, choices)
        }
    }

    fun setPinned(status: TimelineStatus) = mutateStatus(
        successMessage = if (status.pinned) "プロフィールの固定を解除しました" else "プロフィールに固定しました",
    ) { timelineRepository.setPinned(it, status.statusId, !status.pinned) }

    fun deleteStatus(status: TimelineStatus) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        requestScope.launch {
            timelineRepository.deleteStatus(session, status.statusId).forSession(snapshot).fold(
                onSuccess = {
                    browsing.publish(snapshot, BrowsingSession.Change.StatusDeleted(status.statusId))
                    _uiState.update { it.copy(actionMessage = "投稿を削除しました") }
                },
                onFailure = { showActionError(it, "投稿を削除できませんでした") },
            )
        }
    }

    fun unfollow(status: TimelineStatus) = accountAction("${status.author.displayName}さんのフォローを解除しました") {
        timelineRepository.setFollowing(it, status.author.id, false)
    }

    fun mute(status: TimelineStatus) = accountAction("${status.author.displayName}さんをミュートしました") {
        timelineRepository.setMuted(it, status.author.id, true)
    }

    fun block(status: TimelineStatus) = accountAction("${status.author.displayName}さんをブロックしました") {
        timelineRepository.setBlocked(it, status.author.id, true)
    }

    fun report(status: TimelineStatus, comment: String) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        requestScope.launch {
            timelineRepository.reportStatus(session, status.author.id, status.statusId, comment).forSession(snapshot).fold(
                onSuccess = { _uiState.update { it.copy(actionMessage = "通報を送信しました") } },
                onFailure = { showActionError(it, "通報を送信できませんでした") },
            )
        }
    }

    fun loadLists() {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        if (_uiState.value.isLoadingLists) return
        _uiState.update { it.copy(isLoadingLists = true) }
        requestScope.launch {
            timelineRepository.getLists(session).forSession(snapshot).fold(
                onSuccess = { lists -> _uiState.update { it.copy(lists = lists, isLoadingLists = false) } },
                onFailure = { _uiState.update { it.copy(isLoadingLists = false) }; showActionError(it, "リストを取得できませんでした") },
            )
        }
    }

    fun addToList(status: TimelineStatus, listId: String) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        requestScope.launch {
            timelineRepository.addAccountToList(session, listId, status.author.id).forSession(snapshot).fold(
                onSuccess = { _uiState.update { it.copy(actionMessage = "リストに追加しました") } },
                onFailure = { showActionError(it, "リストに追加できませんでした") },
            )
        }
    }

    fun consumeActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    private fun runOptimisticAction(
        status: TimelineStatus,
        begin: (AccountSession, TimelineStatus) -> PendingStatusAction?,
    ) {
        val snapshot = currentSnapshot() ?: return
        val pending = begin(snapshot.account!!, status) ?: return
        requestScope.launch {
            statusActionManager.complete(pending).forSession(snapshot).onFailure { error ->
                val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                    "投稿操作には追加権限が必要です。設定からログアウト後、再ログインしてください。"
                } else error.message ?: "投稿を更新できませんでした"
                _uiState.update { it.copy(actionMessage = message) }
            }
        }
    }

    private fun mutateStatus(
        successMessage: String? = null,
        request: suspend (AccountSession) -> Result<TimelineStatus>,
    ) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        requestScope.launch {
            request(session)
                .forSession(snapshot).onSuccess { updated ->
                    browsing.publish(snapshot, BrowsingSession.Change.StatusUpdated(updated))
                    _uiState.update { it.copy(actionMessage = successMessage) }
                }
                .onFailure { error ->
                    val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
                        "投稿操作には追加権限が必要です。設定からログアウト後、再ログインしてください。"
                    } else error.message ?: "投稿を更新できませんでした"
                    _uiState.update { it.copy(actionMessage = message) }
                }
        }
    }

    private fun accountAction(
        successMessage: String,
        request: suspend (AccountSession) -> Result<*>,
    ) {
        val snapshot = currentSnapshot() ?: return
        val session = snapshot.account!!
        requestScope.launch {
            request(session).forSession(snapshot).fold(
                onSuccess = { _uiState.update { it.copy(actionMessage = successMessage) } },
                onFailure = { showActionError(it, "アカウント操作に失敗しました") },
            )
        }
    }

    private fun showActionError(error: Throwable, fallback: String) {
        _uiState.update { it.copy(actionMessage = error.message ?: fallback) }
    }

}
