package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.data.remote.dto.toDomain
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.ServerAccountSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import retrofit2.HttpException

class DefaultServerAccountSettingsRepository(
    private val clients: ApiClientFactory,
    private val store: UserPreferencesStore?,
    private val scope: CoroutineScope,
    private val registration: AccountDisplaySynchronizer?,
    private val getAccounts: suspend (AccountSession, ModerationListKind, String?) -> Result<ModerationAccountsPage>,
    private val applyAccounts: suspend (AccountSession, ModerationListKind, List<ModerationAccount>) -> Unit,
    private val filtersChanged: (AccountSession) -> Unit = {},
) : ServerAccountSettingsRepository {
    private val mutable = MutableStateFlow<Map<String, ServerAccountSettings>>(emptyMap())
    override val serverSettings = mutable.asStateFlow()
    private val writes = Mutex()
    private data class RequestKey(val sessionId: String, val item: ServerSetting, val epoch: Long?, val generation: Long, val instanceUrl: String, val token: String)
    private val requests = mutableMapOf<RequestKey, Deferred<Result<Unit>>>()
    private val requestLock = Mutex()
    private val generations = mutableMapOf<String, Long>()
    private val versions = mutableMapOf<Pair<String, ServerSetting>, Long>()
    init {
        scope.launch {
            serverSettings.map { settings -> settings.values.flatMap { it.filters.orEmpty() }.mapNotNull { it.expiresAt?.let { date -> runCatching { java.time.Instant.parse(date) }.getOrNull() } }.distinct().sorted() }
                .distinctUntilChanged().collectLatest { deadlines ->
                    while (true) {
                        val next = deadlines.firstOrNull { it.isAfter(java.time.Instant.now()) } ?: break
                        delay(java.time.Duration.between(java.time.Instant.now(), next).toMillis().coerceAtLeast(1))
                        mutable.update { values -> values.mapValues { (_, value) -> value.copy(expiryRevision = value.expiryRevision + 1) } }
                    }
                }
        }
    }
    private fun snapshot(session: AccountSession) = mutable.value[session.sessionId]?.takeIf { it.belongsTo(session) }
        ?: ServerAccountSettings(session.instanceUrl, session.accountId)

    override suspend fun activateServerSettings(session: AccountSession): Unit = requestLock.withLock {
        generations[session.sessionId] = (generations[session.sessionId] ?: 0L) + 1
        requests.filterKeys { it.sessionId == session.sessionId }.values.forEach { it.cancel() }
        requests.keys.removeAll { it.sessionId == session.sessionId }
        Unit
    }

    override suspend fun prepareServerSettings(session: AccountSession) {
        writes.withLock {
            if (mutable.value[session.sessionId]?.belongsTo(session) == true) return
            val saved = store?.preferences?.first()?.serverSettings?.get(session.sessionId)?.takeIf { it.belongsTo(session) }
            val commit: suspend () -> Unit = {
                val value = saved ?: snapshot(session)
                mutable.update { it + (session.sessionId to value) }
                value.mutes?.let { applyAccounts(session, ModerationListKind.Mutes, it) }
                value.blocks?.let { applyAccounts(session, ModerationListKind.Blocks, it) }
            }
            if (registration == null) commit() else registration.withRegistration(session, block = commit)
        }
    }

    override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> = runCatchingCancellable {
        prepareServerSettings(session)
        val epoch = registration?.registrationEpoch()
        coroutineScope {
            items.map { item -> async {
                val (key, task) = requestLock.withLock {
                    currentCoroutineContext().ensureActive()
                    val key = RequestKey(session.sessionId, item, epoch, generations[session.sessionId] ?: 0L, session.instanceUrl, session.accessToken)
                    key to (requests[key]?.takeIf { !it.isCompleted } ?: scope.async { fetch(session, item, epoch) }.also { requests[key] = it })
                }
                try { task.await().getOrThrow() }
                finally { requestLock.withLock { if (task.isCompleted && requests[key] === task) requests.remove(key) } }
            } }.awaitAll()
        }
    }

    private suspend fun fetch(session: AccountSession, item: ServerSetting, epoch: Long?): Result<Unit> {
        val version = writes.withLock {
            mutable.update { values -> values + (session.sessionId to snapshot(session).let { it.copy(fetch = it.fetch + (item to (it.fetch[item] ?: SettingFetch()).copy(loading = true, error = null))) }) }
            versions[session.sessionId to item] ?: 0L
        }
        val result = runCatchingCancellable {
            val api = clients.create(session.instanceUrl, session.accessToken)
            val transform: (ServerAccountSettings) -> ServerAccountSettings = when (item) {
                ServerSetting.Preferences -> {
                    val response = api.getPreferences()
                    fun value(key: String) = (response[key] as? JsonPrimitive)?.contentOrNull
                    val visibility = value("posting:default:visibility")?.takeIf { it in setOf("public", "unlisted", "private") }
                    val quote = value("posting:default:quote_policy")?.takeIf { it in setOf("public", "followers", "nobody") }
                    val mapper: (ServerAccountSettings) -> ServerAccountSettings = { previous -> previous.copy(defaults = PostingDefaults(
                        visibility ?: previous.defaults?.visibility, quote ?: previous.defaults?.quotePolicy,
                        if (response.containsKey("posting:default:language")) value("posting:default:language") else previous.defaults?.language,
                        response.containsKey("posting:default:language") || previous.defaults?.languageKnown == true,
                        quote != null)) }
                    mapper
                }
                ServerSetting.Filters -> { val filters = api.getFilters().map { it.toDomain() }; { it.copy(filters = filters, displayPending = it.displayPending || it.filters != filters, pendingCreation = null,
                    displayChecks = if (it.filters != filters) emptyMap() else it.displayChecks) } }
                ServerSetting.Mutes, ServerSetting.Blocks -> {
                    val kind = if (item == ServerSetting.Mutes) ModerationListKind.Mutes else ModerationListKind.Blocks
                    val accounts = mutableListOf<ModerationAccount>()
                    val cursors = mutableSetOf<String>()
                    var cursor: String? = null
                    do {
                        val page = getAccounts(session, kind, cursor).getOrThrow()
                        accounts += page.accounts
                        cursor = page.nextMaxId
                        check(cursor == null || cursors.add(cursor)) { "一覧の取得位置が繰り返されました" }
                    } while (cursor != null)
                    { previous: ServerAccountSettings -> if (kind == ModerationListKind.Mutes) previous.copy(mutes = accounts.distinctBy { it.account.id }) else previous.copy(blocks = accounts.distinctBy { it.account.id }) }
                }
            }
            commit(session, epoch, item, version) { old -> transform(old).let { it.copy(fetch = it.fetch + (item to SettingFetch(fetchedAt = System.currentTimeMillis()))) } }
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            // Probe the API itself, including compatible servers with non-Mastodon version strings.
            // These codes on the collection endpoint mean unavailable; never try v1 filters.
            val unsupported = error is HttpException && error.code() in setOf(404, 405, 501)
            commit(session, epoch, item, version) { old -> old.copy(fetch = old.fetch + (item to (old.fetch[item] ?: SettingFetch()).copy(loading = false,
                unsupported = unsupported, error = if (unsupported && item == ServerSetting.Filters) null
                else if (unsupported) "このサーバーは未対応です" else if (error is HttpException && error.code() in setOf(401, 403)) "認証・読み取り権限を確認してください。必要なら再ログインしてください" else "取得できませんでした。取得済みの設定があれば引き続き使用します"))) }
        }
        return result
    }

    private suspend fun commit(session: AccountSession, epoch: Long?, item: ServerSetting?, version: Long? = null,
        transform: (ServerAccountSettings) -> ServerAccountSettings) = writes.withLock {
        currentCoroutineContext().ensureActive()
        if (item != null && version != null && (versions[session.sessionId to item] ?: 0L) != version) {
            mutable.update { values -> values + (session.sessionId to snapshot(session).let { it.copy(fetch = it.fetch +
                (item to (it.fetch[item] ?: SettingFetch()).copy(loading = false))) }) }
            return@withLock
        }
        val save: suspend () -> Unit = {
            val old = snapshot(session)
            val value = transform(old)
            mutable.update { it + (session.sessionId to value) }
            if (old.filters != value.filters) filtersChanged(session)
            val persisted = runCatchingCancellable { store?.saveServerSettings(session.sessionId, value) }
            if (persisted.isFailure) mutable.update { values -> values + (session.sessionId to value.copy(fetch = value.fetch +
                ((item ?: ServerSetting.Filters) to (value.fetch[item ?: ServerSetting.Filters] ?: SettingFetch()).copy(error = "サーバー反映済みですが、端末への保存に失敗しました")))) }
            if (old.mutes != value.mutes) value.mutes?.let { applyAccounts(session, ModerationListKind.Mutes, it) }
            if (old.blocks != value.blocks) value.blocks?.let { applyAccounts(session, ModerationListKind.Blocks, it) }
        }
        if (registration == null) save() else registration.withRegistration(session, epoch, save)
    }

    suspend fun recordRelationship(session: AccountSession, author: StatusAuthor, relation: AccountRelationship,
        updateMuteExpiry: Boolean = false, muteExpiresAt: String? = null) {
        prepareServerSettings(session)
        for (item in listOf(ServerSetting.Mutes, ServerSetting.Blocks)) {
            writes.withLock { versions[session.sessionId to item] = (versions[session.sessionId to item] ?: 0L) + 1 }
        }
        commit(session, null, null) { old ->
            fun update(rows: List<ModerationAccount>?, active: Boolean, isMute: Boolean = false): List<ModerationAccount> {
                val updated = rows.orEmpty().mapNotNull {
                    if (it.account.id != author.id) it else if (active) it.copy(relationship = relation,
                        muteExpiresAt = if (isMute && updateMuteExpiry) muteExpiresAt else it.muteExpiresAt) else null
                }
                return if (active && updated.none { it.account.id == author.id }) updated + ModerationAccount(author, relation, muteExpiresAt.takeIf { isMute }) else updated
            }
            old.copy(mutes = update(old.mutes, relation.muting, isMute = true), blocks = update(old.blocks, relation.blocking))
        }
    }

    override suspend fun saveServerFilter(session: AccountSession, original: ServerFilter?, edit: FilterEdit): Result<ServerFilter> {
        var createStarted = false
        suspend fun rememberUncertainCreation() = withContext(NonCancellable) {
            writes.withLock { versions[session.sessionId to ServerSetting.Filters] = (versions[session.sessionId to ServerSetting.Filters] ?: 0L) + 1 }
            commit(session, null, ServerSetting.Filters) { it.copy(pendingCreation = edit) }
        }
        val result = try {
            runCatchingCancellable {
                require(edit.title.isNotBlank() && edit.context.isNotEmpty() && edit.keywords.none { it.keyword.isBlank() }) { "名前・適用画面・キーワードを確認してください" }
                prepareServerSettings(session)
                check(original != null || snapshot(session).pendingCreation == null) { "以前の作成結果を確認中です" }
                val api = clients.create(session.instanceUrl, session.accessToken)
                val fields = filterFields(original, edit)
                createStarted = original == null
                val saved = (if (original == null) api.createFilter(fields, edit.context.toList())
                    else api.updateFilter(original.id, fields, edit.context.toList())).toDomain()
                createStarted = false
                withContext(NonCancellable) {
                    writes.withLock { versions[session.sessionId to ServerSetting.Filters] = (versions[session.sessionId to ServerSetting.Filters] ?: 0L) + 1 }
                    commit(session, null, null) { it.copy(filters = it.filters.orEmpty().filterNot { f -> f.id == saved.id } + saved, displayPending = true, displayChecks = emptyMap()) }
                }
                saved
            }
        } catch (cancelled: CancellationException) {
            if (createStarted) rememberUncertainCreation()
            throw cancelled
        }
        if (createStarted && result.isFailure && result.exceptionOrNull() !is HttpException) rememberUncertainCreation()
        return result
    }
    override suspend fun savePostingVisibility(session: AccountSession, visibility: String): Result<Unit> = runCatchingCancellable {
        require(visibility in setOf("public", "unlisted", "private")) { "サーバーに保存できない公開範囲です" }
        prepareServerSettings(session)
        val epoch = registration?.registrationEpoch()
        val saved = clients.create(session.instanceUrl, session.accessToken).updatePostingVisibility(visibility)
        require(saved.id == session.accountId && saved.source?.privacy == visibility) { "サーバーの保存結果を確認できませんでした。再起動またはアカウント切り替えで確認してください" }
        // Keep an earlier preferences response from overwriting the successful change.
        withContext(NonCancellable) {
            writes.withLock { versions[session.sessionId to ServerSetting.Preferences] = (versions[session.sessionId to ServerSetting.Preferences] ?: 0L) + 1 }
            commit(session, epoch, ServerSetting.Preferences) { old -> old.copy(
                defaults = (old.defaults ?: PostingDefaults()).copy(visibility = visibility),
                fetch = old.fetch + (ServerSetting.Preferences to (old.fetch[ServerSetting.Preferences] ?: SettingFetch()).copy(error = null)),
            ) }
        }
    }.recoverCatching { error ->
        when {
            error is HttpException && error.code() in setOf(401, 403) -> throw IllegalArgumentException("公開範囲の保存権限を確認してください。必要なら再ログインしてください", error)
            error is HttpException && error.code() in setOf(404, 405, 501) -> throw IllegalArgumentException("このサーバーは公開範囲の保存に対応していません", error)
            else -> throw error
        }
    }
    override suspend fun deleteServerFilter(session: AccountSession, id: String): Result<Unit> = runCatchingCancellable {
        val response = clients.create(session.instanceUrl, session.accessToken).deleteFilter(id)
        if (!response.isSuccessful) throw HttpException(response)
        withContext(NonCancellable) {
            writes.withLock { versions[session.sessionId to ServerSetting.Filters] = (versions[session.sessionId to ServerSetting.Filters] ?: 0L) + 1 }
            commit(session, null, null) { it.copy(filters = it.filters.orEmpty().filterNot { f -> f.id == id }, displayPending = true, displayChecks = emptyMap()) }
        }
    }
    override suspend fun updateFilterDisplay(session: AccountSession, screen: String, expectedFilters: List<ServerFilter>, success: Boolean?) {
        commit(session, null, null) {
            if (it.filters != expectedFilters) it else {
                val checks = it.displayChecks + (screen to success)
                it.copy(displayChecks = checks, displayPending = checks.values.any { result -> result != true })
            }
        }
    }
}

internal fun filterFields(original: ServerFilter?, edit: FilterEdit): Map<String, String> = buildMap {
    put("title", edit.title.trim())
    if (original == null || original.action != edit.action || edit.action in setOf("warn", "hide", "blur")) put("filter_action", edit.action)
    if (original == null || edit.changeExpiry) put("expires_in", edit.expiresIn?.toString() ?: "")
    val removed = original?.keywords.orEmpty().filter { old -> edit.keywords.none { it.id != null && it.id == old.id } }
    edit.keywords.forEachIndexed { index, keyword ->
        keyword.id?.let { put("keywords_attributes[$index][id]", it) }
        put("keywords_attributes[$index][keyword]", keyword.keyword)
        put("keywords_attributes[$index][whole_word]", keyword.wholeWord.toString())
    }
    removed.forEachIndexed { index, keyword -> keyword.id?.let {
        put("keywords_attributes[${index + edit.keywords.size}][id]", it)
        put("keywords_attributes[${index + edit.keywords.size}][_destroy]", "true")
    } }
}
