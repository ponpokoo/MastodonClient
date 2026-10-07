package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

enum class ModerationPage { Overview, Words, Mutes, Blocks }
data class ManagementNotice(val id: Long, val message: String, val canUndo: Boolean)
data class ManagementList(val accounts: List<ModerationAccount> = emptyList(), val nextMaxId: String? = null,
    val loaded: Boolean = false, val loading: Boolean = false)
data class ModerationManagementState(
    val sessions: List<AccountSession> = emptyList(), val selected: AccountSession? = null,
    val page: ModerationPage = ModerationPage.Overview, val words: List<String> = emptyList(),
    val lists: Map<ModerationListKind, ManagementList> = emptyMap(), val busy: Boolean = false,
    val error: String? = null, val notice: ManagementNotice? = null,
)

/** The management account is local to this screen, like the composer account picker. */
class ModerationManagementViewModel(private val timeline: TimelineRepository, private val auth: AuthRepository,
    private val wordRepository: WordMuteRepository, private val now: () -> Instant = Instant::now) : ViewModel() {
    private val mutable = MutableStateFlow(ModerationManagementState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var noticeId = 0L
    private val requests = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val scope = CoroutineScope(viewModelScope.coroutineContext + requests)
    private var retryAction: (() -> Unit)? = null
    private var sessionsJob: Job? = null
    private sealed interface Undo {
        data class Word(val word: String, val index: Int, val added: Boolean) : Undo
        data class Account(val kind: ModerationListKind, val entry: ModerationAccount, val index: Int) : Undo
    }
    private var undo: Undo? = null
    init {
        loadSessions()
        viewModelScope.launch {
            wordRepository.words.collect { values -> mutable.update { it.copy(words = values[it.selected?.sessionId].orEmpty()) } }
        }
    }
    private fun loadSessions() {
        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch {
            runCatchingCancellable {
                val sessions = auth.getSessions()
                val selected = auth.restoreSession()?.takeIf { active -> sessions.any { it.sessionId == active.sessionId } } ?: sessions.firstOrNull()
                mutable.update { it.copy(sessions = sessions) }
                selected?.let { selectAccount(it.sessionId) }
                auth.observeSessions().collect { updated ->
                    val previous = state.value.selected
                    val current = updated.firstOrNull { it.sessionId == previous?.sessionId }
                    mutable.update { it.copy(sessions = updated) }
                    if (current != null && current.hasSameCredentials(previous)) {
                        mutable.update { it.copy(selected = current) }
                    } else if (current != null || updated.isNotEmpty()) {
                        selectAccount((current ?: updated.first()).sessionId)
                    } else if (previous != null) {
                        generation++; requests.cancelChildren(); undo = null; retryAction = null
                        mutable.update { it.copy(selected = null, lists = emptyMap(), words = emptyList(),
                            busy = false, error = null, notice = null) }
                    }
                }
            }.onFailure {
                retryAction = ::loadSessions
                mutable.update { it.copy(error = "アカウントを読み込めませんでした") }
            }
        }
    }
    fun selectAccount(id: String) {
        val account = state.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (account.hasSameCredentials(state.value.selected)) return
        generation++; requests.cancelChildren(); undo = null; retryAction = null
        mutable.update { it.copy(selected = account, lists = emptyMap(), words = wordRepository.words.value[id].orEmpty(), busy = false, error = null, notice = null) }
        kind(state.value.page)?.let { load(it) }
    }
    fun open(page: ModerationPage) {
        mutable.update { it.copy(page = page, error = null, notice = null) }; undo = null; retryAction = null
        kind(page)?.let { if (state.value.lists[it]?.loaded != true) load(it) }
    }
    private fun kind(page: ModerationPage) = when (page) { ModerationPage.Mutes -> ModerationListKind.Mutes; ModerationPage.Blocks -> ModerationListKind.Blocks; else -> null }
    fun refresh() { kind(state.value.page)?.let { load(it) } }
    fun loadMore() { kind(state.value.page)?.let { load(it, more = true) } }
    fun retry() { retryAction?.invoke() }
    private fun load(kind: ModerationListKind, more: Boolean = false) {
        val session = state.value.selected ?: return
        val old = state.value.lists[kind] ?: ManagementList()
        if (old.loading || state.value.busy || more && old.nextMaxId == null) return
        val expected = generation
        mutable.update { it.copy(error = null, lists = it.lists + (kind to old.copy(loading = true))) }
        scope.launch {
            val result = timeline.getModerationAccounts(session, kind, if (more) old.nextMaxId else null)
            ensureActive(); if (expected != generation) return@launch
            result.onSuccess { page ->
                retryAction = null
                val rows = (if (more) old.accounts + page.accounts else page.accounts).distinctBy { it.account.id }
                mutable.update { it.copy(lists = it.lists + (kind to ManagementList(rows, page.nextMaxId?.takeUnless { next -> next == old.nextMaxId && more }, loaded = true))) }
            }.onFailure {
                retryAction = { load(kind, more) }
                mutable.update { it.copy(error = "一覧を取得できませんでした", lists = it.lists + (kind to old.copy(loading = false))) }
            }
        }
    }
    fun addWord(value: String) = wordChange(value.trim(), added = true)
    fun removeWord(value: String) = wordChange(value, added = false)
    private fun wordChange(word: String, added: Boolean) {
        val index = state.value.words.indexOf(word)
        action(onRetry = { wordChange(word, added) }) { session ->
            if (added) wordRepository.add(session.sessionId, word) else wordRepository.remove(session.sessionId, word)
            ensureCurrent(session)
            announce("「$word」を${if (added) "追加" else "削除"}しました", Undo.Word(word, index, added))
        }
    }
    fun release(entry: ModerationAccount) {
        val kind = kind(state.value.page) ?: return
        val index = state.value.lists[kind]?.accounts?.indexOfFirst { it.account.id == entry.account.id } ?: return
        action(onRetry = { release(entry) }) { session ->
            val relation = if (kind == ModerationListKind.Mutes) timeline.setMuted(session, entry.account.id, false).getOrThrow()
                else timeline.setBlocked(session, entry.account.id, false).getOrThrow()
            ensureCurrent(session)
            check(if (kind == ModerationListKind.Mutes) !relation.muting else !relation.blocking) { "解除を確認できませんでした" }
            updateRelationship(entry, relation)
            announce("${entry.account.displayName}さんの${if (kind == ModerationListKind.Mutes) "ミュート" else "ブロック"}を解除しました", Undo.Account(kind, entry, index))
        }
    }
    private fun updateRelationship(entry: ModerationAccount, relation: AccountRelationship, restore: Undo.Account? = null) {
        mutable.update { state -> state.copy(lists = state.lists.mapValues { (kind, list) ->
            val active = if (kind == ModerationListKind.Mutes) relation.muting else relation.blocking
            val rows = list.accounts.map { if (it.account.id == entry.account.id) it.copy(relationship = relation) else it }.toMutableList()
            if (!active) rows.removeAll { it.account.id == entry.account.id }
            else if (restore?.kind == kind && rows.none { it.account.id == entry.account.id }) rows.add(restore.index.coerceIn(0, rows.size), entry.copy(relationship = relation))
            list.copy(accounts = rows)
        }) }
    }
    fun undoNotice(id: Long) {
        if (state.value.notice?.id != id) return
        val operation = undo ?: return
        // A refresh started during the snackbar must not overwrite the restored row.
        generation++; requests.cancelChildren()
        mutable.update { it.copy(notice = null, lists = it.lists.mapValues { (_, list) -> list.copy(loading = false) }) }
        undoOperation(operation)
    }
    private fun undoOperation(operation: Undo): Unit = action(onRetry = { undoOperation(operation) }) { session ->
        when (operation) {
            is Undo.Word -> if (operation.added) wordRepository.remove(session.sessionId, operation.word)
                else wordRepository.restore(session.sessionId, operation.word, operation.index)
            is Undo.Account -> {
                val relation = if (operation.kind == ModerationListKind.Blocks) timeline.setBlocked(session, operation.entry.account.id, true).getOrThrow()
                else {
                    val expiry = operation.entry.muteExpiresAt?.let(Instant::parse)
                    val remaining = expiry?.let { java.time.Duration.between(now(), it).seconds }
                    if (remaining != null && remaining <= 0) return@action
                    timeline.restoreMute(session, operation.entry.account.id, operation.entry.relationship.mutingNotifications != false, remaining).getOrThrow()
                }
                check(if (operation.kind == ModerationListKind.Mutes) relation.muting else relation.blocking) { "取り消しを確認できませんでした" }
                ensureCurrent(session)
                updateRelationship(operation.entry, relation, operation)
            }
        }
        ensureCurrent(session); undo = null
    }
    private fun announce(message: String, operation: Undo) {
        undo = operation; mutable.update { it.copy(notice = ManagementNotice(++noticeId, message, true)) }
    }
    fun consumeNotice(id: Long) { if (state.value.notice?.id == id) { undo = null; mutable.update { it.copy(notice = null) } } }
    private suspend fun ensureCurrent(session: AccountSession) {
        currentCoroutineContext().ensureActive()
        if (!session.hasSameCredentials(state.value.selected)) throw CancellationException("Management account changed")
    }
    private fun action(onRetry: () -> Unit, operation: suspend (AccountSession) -> Unit) {
        val session = state.value.selected ?: return
        if (state.value.busy || state.value.lists.values.any { it.loading }) return
        val expected = generation
        mutable.update { it.copy(busy = true, error = null, notice = null) }; undo = null
        scope.launch {
            try {
                runCatchingCancellable {
                    operation(session)
                    ensureActive(); check(expected == generation)
                }.onSuccess { retryAction = null }.onFailure { failure -> if (expected == generation) { retryAction = onRetry; mutable.update { it.copy(error = if (failure is IllegalArgumentException) failure.message else "変更できませんでした。再試行してください") } } }
            } finally { if (expected == generation) mutable.update { it.copy(busy = false) } }
        }
    }
}
