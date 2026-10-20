package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

enum class ModerationPage { Overview, Mutes, Blocks, Filters, Words }
enum class FilterListStatus { NotFetched, Loading, Unsupported, Failed, Ready }
data class FilterEditorState(val original: ServerFilter?, val edit: FilterEdit, val awaitingConfirmation: Boolean = false)
data class ManagementNotice(val id: Long, val message: String, val canUndo: Boolean)
data class ManagementList(val accounts: List<ModerationAccount> = emptyList(), val nextMaxId: String? = null,
    val loaded: Boolean = false, val loading: Boolean = false)
data class ModerationManagementState(
    val sessions: List<AccountSession> = emptyList(), val selected: AccountSession? = null,
    val page: ModerationPage = ModerationPage.Overview,
    val lists: Map<ModerationListKind, ManagementList> = emptyMap(), val busy: Boolean = false,
    val error: String? = null, val notice: ManagementNotice? = null,
    val server: ServerAccountSettings? = null, val editor: FilterEditorState? = null,
    val words: List<String> = emptyList(),
) {
    val filterListStatus: FilterListStatus get() {
        val fetch = server?.fetch?.get(ServerSetting.Filters)
        return when {
            fetch?.loading == true -> FilterListStatus.Loading
            fetch?.unsupported == true -> FilterListStatus.Unsupported
            fetch?.error != null -> FilterListStatus.Failed
            server?.filters == null -> FilterListStatus.NotFetched
            else -> FilterListStatus.Ready
        }
    }
}

/** The management account is local to this screen, like the composer account picker. */
class ModerationManagementViewModel(private val timeline: TimelineRepository, private val auth: AuthRepository,
    private val wordRepository: WordMuteRepository? = null,
    private val now: () -> Instant = Instant::now) : ViewModel() {
    private val mutable = MutableStateFlow(ModerationManagementState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var noticeId = 0L
    private val requests = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val scope = CoroutineScope(viewModelScope.coroutineContext + requests)
    private var retryAction: (() -> Unit)? = null
    private var sessionsJob: Job? = null
    private val visibleCounts = mutableMapOf<ModerationListKind, Int>()
    private sealed interface Undo {
        data class Word(val word: String, val index: Int, val added: Boolean) : Undo
        data class Account(val kind: ModerationListKind, val entry: ModerationAccount, val index: Int) : Undo
    }
    private var undo: Undo? = null
    init {
        loadSessions()
        viewModelScope.launch { timeline.serverSettings.collect { presentServerSettings() } }
        viewModelScope.launch {
            wordRepository?.words?.collect { values -> mutable.update { it.copy(words = values[it.selected?.sessionId].orEmpty()) } }
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
                            server = null, editor = null, busy = false, error = null, notice = null) }
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
        val previousSelected = state.value.selected
        generation++; requests.cancelChildren(); undo = null; retryAction = null
        mutable.update { it.copy(selected = account, lists = emptyMap(), words = wordRepository?.words?.value?.get(id).orEmpty(), busy = false, error = null, notice = null) }
        visibleCounts.clear()
        mutable.update { it.copy(server = null, editor = null) }
        val expected = generation
        scope.launch {
            if (previousSelected != null) timeline.activateServerSettings(account)
            timeline.prepareServerSettings(account)
            ensureCurrent(account); if (expected != generation) return@launch
            presentServerSettings()
            if (previousSelected != null) timeline.syncServerSettings(account)
        }
    }
    fun open(page: ModerationPage) {
        mutable.update { it.copy(page = page, error = null, notice = null) }; undo = null; retryAction = null
        presentServerSettings()
    }
    private fun kind(page: ModerationPage) = when (page) { ModerationPage.Mutes -> ModerationListKind.Mutes; ModerationPage.Blocks -> ModerationListKind.Blocks; else -> null }
    fun refresh() {
        val account = state.value.selected ?: return
        if (state.value.busy) return
        val items = when (state.value.page) {
            ModerationPage.Mutes, ModerationPage.Blocks -> setOf(ServerSetting.Mutes, ServerSetting.Blocks)
            ModerationPage.Filters -> setOf(ServerSetting.Filters)
            ModerationPage.Overview, ModerationPage.Words -> return
        }
        val expected = generation
        retryAction = null
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                val result = timeline.syncServerSettings(account, items)
                ensureCurrent(account); if (expected != generation) return@launch
                presentServerSettings()
                result.onFailure {
                    retryAction = ::refresh
                    val unsupported = items == setOf(ServerSetting.Filters) && state.value.server?.fetch?.get(ServerSetting.Filters)?.unsupported == true
                    if (!unsupported) mutable.update { it.copy(error = "更新できませんでした。再試行してください") }
                }
            } finally { if (expected == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun loadMore() { kind(state.value.page)?.let { visibleCounts[it] = (visibleCounts[it] ?: 40) + 40; presentServerSettings() } }
    fun retry() { retryAction?.invoke() }
    private fun presentServerSettings() {
        val account = state.value.selected ?: return
        val saved = timeline.serverSettings.value[account.sessionId]?.takeIf { it.belongsTo(account) } ?: return
        val lists = listOf(ModerationListKind.Mutes, ModerationListKind.Blocks).associateWith { kind ->
            val rows = if (kind == ModerationListKind.Mutes) saved.mutes.orEmpty() else saved.blocks.orEmpty()
            val item = if (kind == ModerationListKind.Mutes) ServerSetting.Mutes else ServerSetting.Blocks
            val count = visibleCounts[kind] ?: 40
            ManagementList(rows.take(count), if (rows.size > count) "cached" else null,
                loaded = saved.fetch[item]?.fetchedAt != null, loading = saved.fetch[item]?.loading == true)
        }
        mutable.update { current ->
            val editor = current.editor
            val notice = if (current.page == ModerationPage.Filters && saved.displayPending && current.server?.displayPending != true && current.notice == null)
                ManagementNotice(++noticeId, "フィルターは保存済みです。投稿への反映待ちです", false) else current.notice
            if (editor?.awaitingConfirmation == true && saved.pendingCreation == null) {
                val confirmed = saved.filters?.any { it.title == editor.edit.title.trim() && it.context == editor.edit.context &&
                    it.action == editor.edit.action && it.keywords.map { word -> word.keyword to word.wholeWord } == editor.edit.keywords.map { word -> word.keyword to word.wholeWord } } == true
                current.copy(server = saved, lists = lists, editor = if (confirmed) null else editor.copy(awaitingConfirmation = false),
                    error = null, notice = if (confirmed) ManagementNotice(++noticeId, "フィルターの作成を確認しました", false) else current.notice)
            } else current.copy(server = saved, lists = lists, notice = notice)
        }
    }
    fun editFilter(original: ServerFilter?) {
        mutable.update { it.copy(editor = FilterEditorState(original, FilterEdit(original?.title.orEmpty(),
            original?.context ?: setOf("home", "notifications"), original?.action ?: "warn",
            original?.keywords ?: listOf(FilterKeyword(keyword = "")))), error = null) }
    }
    fun changeFilter(edit: FilterEdit) { if (!state.value.busy) mutable.update { it.copy(editor = it.editor?.copy(edit = edit)) } }
    fun closeFilterEditor() { if (!state.value.busy) mutable.update { it.copy(editor = null, error = null) } }
    fun saveFilter() {
        val editor = state.value.editor ?: return
        if (editor.awaitingConfirmation) return
        action(onRetry = ::saveFilter) { session ->
            val result = timeline.saveServerFilter(session, editor.original, editor.edit)
            ensureCurrent(session)
            result.onFailure { error ->
                if (editor.original == null && timeline.serverSettings.value[session.sessionId]?.pendingCreation != null) {
                    mutable.update { it.copy(editor = it.editor?.copy(awaitingConfirmation = true)) }
                    retryAction = null
                    throw IllegalArgumentException("作成結果を確認できませんでした。入力を保持し、次の同期を待ちます")
                }
            }.getOrThrow()
            mutable.update { it.copy(editor = null, notice = ManagementNotice(++noticeId, "フィルターを保存しました", false)) }
        }
    }
    fun deleteFilter(filter: ServerFilter): Unit = action(onRetry = { deleteFilter(filter) }) { session ->
        timeline.deleteServerFilter(session, filter.id).getOrThrow(); ensureCurrent(session)
        mutable.update { it.copy(notice = ManagementNotice(++noticeId, "フィルターを削除しました", false)) }
    }
    fun addWord(value: String) = wordChange(value.trim(), added = true)
    fun removeWord(value: String) = wordChange(value, added = false)
    private fun wordChange(word: String, added: Boolean) {
        val index = state.value.words.indexOf(word)
        action(onRetry = { wordChange(word, added) }) { session ->
            val repository = requireNotNull(wordRepository)
            if (added) repository.add(session.sessionId, word) else repository.remove(session.sessionId, word)
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
        mutable.update { it.copy(notice = null, busy = false, lists = it.lists.mapValues { (_, list) -> list.copy(loading = false) }) }
        undoOperation(operation)
    }
    private fun undoOperation(operation: Undo): Unit = action(onRetry = { undoOperation(operation) }) { session ->
        when (operation) {
            is Undo.Word -> {
                val repository = requireNotNull(wordRepository)
                if (operation.added) repository.remove(session.sessionId, operation.word)
                else repository.restore(session.sessionId, operation.word, operation.index)
            }
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
        if (state.value.busy || state.value.page != ModerationPage.Words && state.value.lists.values.any { it.loading }) return
        val expected = generation
        mutable.update { it.copy(busy = true, error = null, notice = null) }; undo = null
        scope.launch {
            try {
                runCatchingCancellable {
                    operation(session)
                    ensureActive(); check(expected == generation)
                }.onSuccess { retryAction = null }.onFailure { failure -> if (expected == generation) { retryAction = onRetry.takeUnless { state.value.editor?.awaitingConfirmation == true }; mutable.update { it.copy(error = if (failure is IllegalArgumentException) failure.message else "変更できませんでした。再試行してください") } } }
            } finally { if (expected == generation) mutable.update { it.copy(busy = false) } }
        }
    }
}
