package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AccountModerationMenuState(
    val accountId: String? = null,
    val relationship: AccountRelationship? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Each screen owns its menu; requests never use another account or a superseded menu response. */
class AccountModerationMenu(
    private val repository: TimelineRepository,
    private val scope: CoroutineScope,
    private val session: () -> AccountSession?,
    private val context: () -> Any?,
    private val isCurrent: suspend (AccountSession) -> Boolean = { true },
    private val message: (String) -> Unit,
    private val updated: (String, AccountRelationship) -> Unit = { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutableState = MutableStateFlow(AccountModerationMenuState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var mutationJob: Job? = null
    private var loadedSession: AccountSession? = null
    private var loadedContext: Any? = null
    private var loadedAt: Long? = null

    /** Also reuse the relationship already fetched for a profile header. */
    fun remember(accountId: String, relationship: AccountRelationship) {
        if (mutableState.value.let { it.busy || it.loading || (it.accountId != null && it.accountId != accountId) }) return
        loadedSession = session()
        loadedContext = context()
        loadedAt = nanoTime()
        mutableState.value = AccountModerationMenuState(accountId, relationship)
    }

    fun reset() {
        generation++
        loadJob?.cancel()
        mutationJob?.cancel()
        loadedAt = null
        loadedSession = null
        loadedContext = null
        mutableState.value = AccountModerationMenuState()
    }

    fun load(accountId: String?) {
        val current = session() ?: return
        val snapshot = context()
        val before = mutableState.value
        if (accountId != null && before.accountId == accountId && before.relationship != null &&
            loadedSession == current && loadedContext == snapshot && loadedAt?.let { nanoTime() - it < 60_000_000_000L } == true) {
            repository.moderation.value.relationship(current, accountId)?.let { relationship ->
                mutableState.update { it.copy(relationship = relationship) }
            }
            return
        }
        if (accountId != null && before.accountId == accountId && before.loading &&
            loadedSession == current && loadedContext == snapshot) return
        val revision = ++generation
        loadJob?.cancel()
        loadedAt = null
        loadedSession = current
        loadedContext = snapshot
        mutableState.value = AccountModerationMenuState(accountId, loading = accountId != null,
            busy = mutationJob?.isActive == true)
        if (accountId == null) return
        loadJob = scope.launch {
            if (!isCurrent(current)) return@launch
            val result = repository.getRelationship(current, accountId)
            currentCoroutineContext().ensureActive()
            if (revision != generation || snapshot != context() || !isCurrent(current)) return@launch
            result.fold(
                { loadedAt = nanoTime(); mutableState.update { state -> state.copy(relationship = it, loading = false) }; updated(accountId, it) },
                { mutableState.update { state -> state.copy(loading = false, error = "関係状態を取得できませんでした") } },
            )
        }
    }

    fun mute(accountId: String, enabled: Boolean) = mutate(accountId,
        if (enabled) "ミュートしました" else "ミュートを解除しました") { repository.setMuted(it, accountId, enabled) }

    fun block(accountId: String, enabled: Boolean) = mutate(accountId,
        if (enabled) "ブロックしました" else "ブロックを解除しました") { repository.setBlocked(it, accountId, enabled) }

    private fun mutate(accountId: String, success: String,
        request: suspend (AccountSession) -> Result<AccountRelationship>) {
        if (mutationJob?.isActive == true) return
        val before = mutableState.value
        if (before.accountId != accountId || before.relationship == null || before.loading) return
        val current = session() ?: return
        val snapshot = context()
        loadJob?.cancel()
        generation++
        mutableState.update { it.copy(busy = true, error = null) }
        mutationJob = scope.launch {
            try {
                if (!isCurrent(current)) return@launch
                val result = request(current)
                currentCoroutineContext().ensureActive()
                if (snapshot != context() || !isCurrent(current)) return@launch
                result.fold(
                    { relationship ->
                        if (mutableState.value.accountId == accountId) {
                            loadedAt = nanoTime()
                            mutableState.update { it.copy(relationship = relationship) }
                        }
                        updated(accountId, relationship)
                        message(success + if (repository.moderation.value.hasCleanupFailure(current, accountId))
                            "。端末の保存内容を更新できませんでした。オンラインで一覧を更新してください。" else "")
                    },
                    { message(it.message ?: "操作に失敗しました") },
                )
            } finally {
                if (snapshot == context()) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
