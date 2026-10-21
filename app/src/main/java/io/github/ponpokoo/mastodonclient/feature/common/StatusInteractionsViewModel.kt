package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.AccountListPage
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
    data class Boosters(override val statusId: String) : StatusAccountsRequest
    data class Reaction(override val statusId: String, val reaction: EmojiReaction) : StatusAccountsRequest
}

data class StatusAccountsUiState(
    val request: StatusAccountsRequest? = null,
    val accounts: List<StatusAuthor> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
)

/** Kept with the main navigation entry, including its detail/profile destinations. */
class StatusInteractionsViewModel(
    private val repository: TimelineRepository,
    browsing: BrowsingSession,
    private val preferences: UserPreferencesStore? = null,
) : SessionScopedViewModel(browsing) {
    private val state = MutableStateFlow(StatusAccountsUiState())
    val accounts = state.asStateFlow()
    private var accountsJob: Job? = null
    private var generation = 0L
    private val completedCursors = mutableSetOf<String>()
    private val emojiCache = mutableMapOf<String, List<CustomEmoji>>()

    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        dismissAccounts()
    }

    fun openFavourites(statusId: String) = open(StatusAccountsRequest.Favourites(statusId))
    fun openBoosters(statusId: String) = open(StatusAccountsRequest.Boosters(statusId))
    fun openReaction(statusId: String, reaction: EmojiReaction) = open(StatusAccountsRequest.Reaction(statusId, reaction))

    fun dismissAccounts() {
        generation++
        accountsJob?.cancel()
        accountsJob = null
        completedCursors.clear()
        state.value = StatusAccountsUiState()
    }

    private fun open(request: StatusAccountsRequest) {
        if (currentSnapshot() == null) return
        dismissAccounts()
        state.value = StatusAccountsUiState(request = request)
        loadPage(null)
    }

    fun loadMoreAccounts() {
        val current = state.value
        if (current.isLoading || current.error != null || current.endReached) return
        loadPage(current.nextMaxId ?: return)
    }

    fun retryAccounts() {
        if (state.value.error == null || state.value.isLoading) return
        loadPage(state.value.nextMaxId)
    }

    private fun loadPage(initialCursor: String?) {
        val snapshot = currentSnapshot() ?: return
        val account = snapshot.account!!
        val request = state.value.request ?: return
        val requestGeneration = generation
        state.update { it.copy(isLoading = true, error = null) }
        accountsJob = requestScope.launch {
            var cursor = initialCursor
            while (true) {
                val result = when (request) {
                    is StatusAccountsRequest.Favourites -> repository.getFavouritedBy(account, request.statusId, cursor)
                    is StatusAccountsRequest.Boosters -> repository.getRebloggedBy(account, request.statusId, cursor)
                    is StatusAccountsRequest.Reaction -> repository.getEmojiReactionedBy(account, request.statusId, request.reaction.name, cursor)
                }.forSession(snapshot)
                if (requestGeneration != generation) return@launch
                val page: AccountListPage = result.getOrElse { error ->
                    state.update { it.copy(isLoading = false, nextMaxId = cursor,
                        error = error.message?.takeIf { message -> message.length <= 100 } ?: "一覧を取得できませんでした") }
                    return@launch
                }
                cursor?.let(completedCursors::add)
                val next = page.nextMaxId?.takeUnless { page.endReached || it in completedCursors }
                val filtered = if (request is StatusAccountsRequest.Reaction && request.reaction.accountIds.isNotEmpty())
                    page.accounts.filter { it.id in request.reaction.accountIds } else page.accounts
                val previous = state.value.accounts
                val merged = (previous + filtered).distinctBy(StatusAuthor::id)
                // A reaction page can contain only other emoji or already displayed accounts.
                // Continue with the server cursor until there is something to display or no next page.
                val continueLoading = merged.size == previous.size && next != null
                state.update { it.copy(accounts = merged, nextMaxId = next,
                    endReached = next == null, isLoading = continueLoading) }
                if (!continueLoading) return@launch
                cursor = next
            }
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

    suspend fun loadEmojiFavorites(sessionId: String): List<String> {
        val snapshot = currentSnapshot()?.takeIf { it.account?.sessionId == sessionId }
            ?: throw kotlinx.coroutines.CancellationException("Account changed")
        val favorites = preferences?.emojiFavorites?.first()?.get(sessionId).orEmpty()
        Result.success(favorites).forSession(snapshot)
        return favorites
    }

    suspend fun setEmojiFavorite(sessionId: String, emoji: String, favorite: Boolean): List<String> {
        val snapshot = currentSnapshot()?.takeIf { it.account?.sessionId == sessionId }
            ?: throw kotlinx.coroutines.CancellationException("Account changed")
        val favorites = preferences?.setEmojiFavorite(sessionId, emoji, favorite)
            ?: error("お気に入りを保存できませんでした")
        Result.success(favorites).forSession(snapshot)
        return favorites
    }
}
