package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface StatusAccountsRequest {
    val statusId: String
    data class Favourites(override val statusId: String) : StatusAccountsRequest
    data class Reaction(override val statusId: String, val reaction: EmojiReaction) : StatusAccountsRequest
}

data class StatusAccountsUiState(
    val request: StatusAccountsRequest? = null,
    val accounts: List<StatusAuthor> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** Kept with the main navigation entry, including its detail/profile destinations. */
class StatusInteractionsViewModel(
    private val repository: TimelineRepository,
    browsing: BrowsingSession,
    private val preferences: UserPreferencesStore? = null,
) : SessionScopedViewModel(browsing) {
    private enum class ListKind { Favourites, Reaction }
    private val states = ListKind.entries.associateWith { MutableStateFlow(StatusAccountsUiState()) }
    val favourites = states.getValue(ListKind.Favourites).asStateFlow()
    val reactions = states.getValue(ListKind.Reaction).asStateFlow()
    private val jobs = mutableMapOf<ListKind, Job>()
    private val generations = mutableMapOf<ListKind, Long>()
    private val emojiCache = mutableMapOf<String, List<CustomEmoji>>()

    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        ListKind.entries.forEach(::dismiss)
    }

    fun openFavourites(statusId: String) = load(StatusAccountsRequest.Favourites(statusId))
    fun openReaction(statusId: String, reaction: EmojiReaction) = load(StatusAccountsRequest.Reaction(statusId, reaction))
    fun dismissFavourites() = dismiss(ListKind.Favourites)
    fun dismissReactions() = dismiss(ListKind.Reaction)

    private fun dismiss(kind: ListKind) {
        generations[kind] = (generations[kind] ?: 0) + 1
        jobs.remove(kind)?.cancel()
        states.getValue(kind).value = StatusAccountsUiState()
    }

    private fun load(request: StatusAccountsRequest) {
        val snapshot = currentSnapshot() ?: return
        val account = snapshot.account!!
        val kind = when (request) {
            is StatusAccountsRequest.Favourites -> ListKind.Favourites
            is StatusAccountsRequest.Reaction -> ListKind.Reaction
        }
        dismiss(kind)
        val generation = generations.getValue(kind)
        val state = states.getValue(kind)
        state.value = StatusAccountsUiState(request = request, isLoading = true)
        jobs[kind] = requestScope.launch {
            val result = when (request) {
                is StatusAccountsRequest.Favourites -> repository.getFavouritedBy(account, request.statusId)
                is StatusAccountsRequest.Reaction -> repository.getEmojiReactionedBy(account, request.statusId, request.reaction.name)
            }.forSession(snapshot)
            if (generation != generations[kind]) return@launch
            result.fold(
                onSuccess = { accounts ->
                    val filtered = if (request is StatusAccountsRequest.Reaction && request.reaction.accountIds.isNotEmpty())
                        accounts.filter { it.id in request.reaction.accountIds } else accounts
                    state.update { it.copy(accounts = filtered, isLoading = false) }
                },
                onFailure = { error -> state.update { it.copy(isLoading = false,
                    error = error.message?.takeIf { message -> message.length <= 100 } ?: when (request) {
                        is StatusAccountsRequest.Favourites -> "お気に入りしたアカウントを取得できませんでした"
                        is StatusAccountsRequest.Reaction -> "一覧を取得できませんでした"
                    }) } },
            )
        }
    }

    suspend fun loadCustomEmojis(): Result<List<CustomEmoji>> = requestScope.async {
        val snapshot = currentSnapshot() ?: return@async Result.failure(IllegalStateException("ログインし直してください"))
        val account = snapshot.account!!
        emojiCache[account.instanceUrl]?.let { return@async Result.success(it) }
        repository.getCustomEmojis(account).forSession(snapshot).onSuccess { emojiCache[account.instanceUrl] = it }
    }.await()

    suspend fun loadReactionHistory(): List<String> {
        val snapshot = currentSnapshot() ?: return emptyList()
        val history = preferences?.reactionHistory?.first()?.get(snapshot.account!!.sessionId).orEmpty()
        Result.success(history).forSession(snapshot)
        return history
    }

    suspend fun saveReactionHistory(emojis: List<String>) {
        val snapshot = currentSnapshot() ?: return
        preferences?.setReactionHistory(snapshot.account!!.sessionId, emojis)
        Result.success(Unit).forSession(snapshot)
    }
}
