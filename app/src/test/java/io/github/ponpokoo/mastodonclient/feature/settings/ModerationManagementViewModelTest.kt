package io.github.ponpokoo.mastodonclient.feature.settings

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationManagementViewModelTest : ScreenViewModelTestBase() {
    @Test fun filterFetchStatesDoNotReportFailureOrUnsupportedAsEmptyAndLocalWordsRemainEditable() = runTest(dispatcher) {
        val repository = Repository()
        val local = io.github.ponpokoo.mastodonclient.data.repository.DefaultWordMuteRepository(
            io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore(
                io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences()), backgroundScope)
        val model = own(ModerationManagementViewModel(repository, auth, wordRepository = local))
        advanceUntilIdle(); model.open(ModerationPage.Filters)
        fun set(filters: List<ServerFilter>?, fetch: SettingFetch) {
            repository.serverSettings.value = repository.serverSettings.value + (testAccount.sessionId to ServerAccountSettings(
                testAccount.instanceUrl, testAccount.accountId, filters = filters, fetch = mapOf(ServerSetting.Filters to fetch)))
        }
        set(null, SettingFetch()); advanceUntilIdle()
        assertEquals(FilterListStatus.NotFetched, model.state.value.filterListStatus)
        set(null, SettingFetch(loading = true)); advanceUntilIdle()
        assertEquals(FilterListStatus.Loading, model.state.value.filterListStatus)
        for ((fetch, expected) in listOf(SettingFetch(unsupported = true) to FilterListStatus.Unsupported,
            SettingFetch(error = "offline") to FilterListStatus.Failed)) {
            set(null, fetch); advanceUntilIdle()
            assertEquals(expected, model.state.value.filterListStatus)
            model.open(ModerationPage.Words); model.addWord("spoiler"); advanceUntilIdle(); runCurrent()
            assertEquals(listOf("spoiler"), model.state.value.words)
            model.removeWord("spoiler"); advanceUntilIdle(); runCurrent()
            model.undoNotice(model.state.value.notice!!.id); advanceUntilIdle(); runCurrent()
            assertEquals(listOf("spoiler"), model.state.value.words)
            model.selectAccount(secondAccount.sessionId); advanceUntilIdle()
            assertTrue(model.state.value.words.isEmpty())
            model.selectAccount(testAccount.sessionId); advanceUntilIdle()
            assertEquals(listOf("spoiler"), model.state.value.words)
            model.removeWord("spoiler"); advanceUntilIdle(); runCurrent(); model.open(ModerationPage.Filters)
        }
        set(emptyList(), SettingFetch(fetchedAt = 1)); advanceUntilIdle()
        assertEquals(FilterListStatus.Ready, model.state.value.filterListStatus)
        set(emptyList(), SettingFetch(fetchedAt = 1, error = "offline")); advanceUntilIdle()
        assertEquals(FilterListStatus.Failed, model.state.value.filterListStatus)
        assertTrue(model.state.value.server!!.filters!!.isEmpty())
    }

    @Test fun filterDisplayPendingUsesNoticeOncePerTransitionAndDoesNotReplaceOperationNotice() = runTest(dispatcher) {
        val repository = Repository()
        val model = model(repository); advanceUntilIdle(); model.open(ModerationPage.Filters)
        fun pending(value: Boolean) {
            repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(displayPending = value) }
        }
        pending(true); advanceUntilIdle()
        val notice = model.state.value.notice!!
        assertFalse(notice.canUndo)
        model.consumeNotice(notice.id)
        repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(displayChecks = mapOf("home" to false)) }
        advanceUntilIdle(); assertNull(model.state.value.notice)
        pending(false); advanceUntilIdle(); pending(true); advanceUntilIdle()
        assertTrue(model.state.value.notice!!.id > notice.id)
        model.consumeNotice(model.state.value.notice!!.id)
        model.open(ModerationPage.Mutes); model.release(entry); advanceUntilIdle()
        val releaseNotice = model.state.value.notice!!
        pending(false); advanceUntilIdle(); pending(true); advanceUntilIdle()
        assertEquals(releaseNotice, model.state.value.notice)
        assertTrue(model.state.value.notice!!.canUndo)
    }

    @Test fun manualFilterRefreshTargetsOnlyFiltersAndFailureCanRetryWithoutLosingCache() = runTest(dispatcher) {
        val original = ServerFilter("old", "保存済み", setOf("notifications"))
        val updated = original.copy(title = "別クライアントで変更")
        val gate = CompletableDeferred<Result<Unit>>()
        val syncs = mutableListOf<Pair<String, Set<ServerSetting>>>()
        val repository = object : ScreenRepositoryFake() {
            override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to ServerAccountSettings(
                testAccount.instanceUrl, testAccount.accountId, filters = listOf(original))))
            override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> {
                syncs += session.sessionId to items
                if (syncs.size == 1) return gate.await()
                serverSettings.value = serverSettings.value.mapValues { (_, saved) -> saved.copy(filters = listOf(updated)) }
                return Result.success(Unit)
            }
        }
        val model = own(ModerationManagementViewModel(repository, auth)); advanceUntilIdle()
        model.refresh(); advanceUntilIdle(); assertTrue(syncs.isEmpty())
        model.open(ModerationPage.Filters); advanceUntilIdle(); assertTrue(syncs.isEmpty())
        model.refresh(); runCurrent(); model.refresh(); runCurrent()
        assertEquals(listOf(testAccount.sessionId to setOf(ServerSetting.Filters)), syncs)
        assertTrue(model.state.value.busy)
        gate.complete(Result.failure(java.io.IOException("offline"))); advanceUntilIdle()
        assertEquals(listOf(original), model.state.value.server!!.filters)
        assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
        model.retry(); advanceUntilIdle()
        assertEquals(2, syncs.size)
        assertTrue(syncs.all { it == testAccount.sessionId to setOf(ServerSetting.Filters) })
        assertEquals(listOf(updated), model.state.value.server!!.filters)
        assertNull(model.state.value.error); assertFalse(model.state.value.busy)
    }
    @Test fun filterFailurePreservesInputAndUncertainCreationCannotBeResentUntilConfirmed() = runTest(dispatcher) {
        val edit = FilterEdit(" warning ", setOf("notifications"), "warn", listOf(FilterKeyword(keyword = "word")), expiresIn = 3600, changeExpiry = true)
        val saved = ServerFilter("new", "warning", edit.context, action = edit.action, keywords = edit.keywords)
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to ServerAccountSettings(testAccount.instanceUrl, testAccount.accountId, filters = emptyList())))
            override suspend fun saveServerFilter(session: AccountSession, original: ServerFilter?, edit: FilterEdit): Result<ServerFilter> {
                if (++calls == 1) return Result.failure(retrofit2.HttpException(retrofit2.Response.error<Any>(403, "forbidden".toResponseBody())))
                serverSettings.value = serverSettings.value.mapValues { (_, value) -> value.copy(pendingCreation = edit) }
                return Result.failure(java.io.IOException("response lost"))
            }
        }
        val model = own(ModerationManagementViewModel(repository, auth)); advanceUntilIdle()
        model.editFilter(null); model.changeFilter(edit); model.saveFilter(); advanceUntilIdle()
        assertEquals(edit, model.state.value.editor!!.edit)
        assertFalse(model.state.value.editor!!.awaitingConfirmation)
        assertNotNull(model.state.value.error)
        model.retry(); advanceUntilIdle()
        assertTrue(model.state.value.editor!!.awaitingConfirmation)
        model.saveFilter(); model.retry(); advanceUntilIdle(); assertEquals(2, calls)
        repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, value) -> value.copy(filters = listOf(saved), pendingCreation = null) }
        advanceUntilIdle()
        assertNull(model.state.value.editor); assertNull(model.state.value.error)
        assertEquals("フィルターの作成を確認しました", model.state.value.notice!!.message)
        assertEquals(2, calls)
    }
    @Test fun sharedAccountOrderKeepsManagementSelectionAndPendingPage() = runTest(dispatcher) {
        val sessions = MutableStateFlow(listOf(testAccount, secondAccount))
        val liveAuth = object : AuthRepository by auth {
            override fun observeSessions() = sessions
            override suspend fun getSessions() = sessions.value
        }
        val repository = Repository()
        val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        repository.request = { _, _ -> gate.await() }
        val model = own(ModerationManagementViewModel(repository, liveAuth))
        runCurrent(); model.selectAccount(secondAccount.sessionId); model.open(ModerationPage.Mutes); runCurrent()
        sessions.value = listOf(secondAccount, testAccount)
        runCurrent()
        assertEquals(sessions.value, model.state.value.sessions)
        assertEquals(secondAccount, model.state.value.selected)
        assertTrue(model.state.value.lists.getValue(ModerationListKind.Mutes).loading)
        gate.complete(Result.success(ModerationAccountsPage(listOf(entry), null))); runCurrent()
        assertEquals(listOf(entry), model.state.value.lists.getValue(ModerationListKind.Mutes).accounts)
        assertEquals(listOf(secondAccount.sessionId to null), repository.requests)
    }
    private val entry = ModerationAccount(testStatus().author, AccountRelationship(muting = true, blocking = true, mutingNotifications = false), "2026-10-05T00:01:00Z")
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = testAccount
        override suspend fun getSessions() = listOf(testAccount, secondAccount)
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
    }
    private inner class Repository : ScreenRepositoryFake() {
        override val serverSettings = MutableStateFlow(listOf(testAccount, secondAccount).associate { account -> account.sessionId to ServerAccountSettings(
            account.instanceUrl, account.accountId, mutes = listOf(entry), blocks = listOf(entry),
            fetch = mapOf(ServerSetting.Mutes to SettingFetch(fetchedAt = 1), ServerSetting.Blocks to SettingFetch(fetchedAt = 1))) })
        val syncs = mutableListOf<Set<ServerSetting>>()
        override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> {
            syncs += items
            serverSettings.value = serverSettings.value + (session.sessionId to serverSettings.value.getValue(session.sessionId).let { it.copy(fetch = it.fetch.mapValues { (_, fetch) -> fetch.copy(loading = true) }) })
            val rows = getModerationAccounts(session, ModerationListKind.Mutes, null)
            currentCoroutineContext().ensureActive()
            rows.onSuccess { page -> serverSettings.value = serverSettings.value + (session.sessionId to serverSettings.value.getValue(session.sessionId).let { it.copy(mutes = page.accounts, blocks = page.accounts,
                fetch = it.fetch.mapValues { (_, fetch) -> fetch.copy(loading = false) }) }) }
            return rows.map { Unit }
        }
        var relation = entry.relationship
        var fail = false
        var request: (suspend (AccountSession, String?) -> Result<ModerationAccountsPage>)? = null
        val requests = mutableListOf<Pair<String, String?>>()
        var restored: Pair<Boolean, Long?>? = null
        var changeGate: CompletableDeferred<Result<AccountRelationship>>? = null
        override suspend fun getModerationAccounts(session: AccountSession, kind: ModerationListKind, maxId: String?): Result<ModerationAccountsPage> {
            requests += session.sessionId to maxId
            return request?.invoke(session, maxId) ?: Result.success(ModerationAccountsPage(listOf(entry.copy(relationship = relation)), null))
        }
        override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
            changeGate?.let { return withContext(NonCancellable) { it.await() } }
            if (fail) return Result.failure(IllegalStateException("offline"))
            relation = relation.copy(muting = muted); return Result.success(relation)
        }
        override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> {
            if (fail) return Result.failure(IllegalStateException("offline"))
            relation = relation.copy(blocking = blocked); return Result.success(relation)
        }
        override suspend fun restoreMute(session: AccountSession, accountId: String, notifications: Boolean, durationSeconds: Long?): Result<AccountRelationship> {
            restored = notifications to durationSeconds
            return setMuted(session, accountId, true)
        }
    }
    private fun model(repository: Repository) = own(ModerationManagementViewModel(repository, auth) { Instant.parse("2026-10-05T00:00:00Z") })

    @Test fun unmuteUndoRestoresOnlyMuteAndPreservesNotificationsAndExpiry() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); advanceUntilIdle()
        model.open(ModerationPage.Mutes); advanceUntilIdle(); model.release(entry); advanceUntilIdle()
        assertTrue(model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.isEmpty())
        assertFalse(repository.relation.muting); assertTrue(repository.relation.blocking)
        model.undoNotice(model.state.value.notice!!.id); advanceUntilIdle()
        assertEquals(false to 60L, repository.restored)
        assertTrue(repository.relation.muting); assertTrue(repository.relation.blocking)
        assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
    }
    @Test fun failedReleaseAndFailedUndoKeepConfirmedStateAndCanRetry() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); advanceUntilIdle()
        model.open(ModerationPage.Blocks); advanceUntilIdle(); repository.fail = true
        model.release(entry); advanceUntilIdle()
        assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Blocks).accounts.size)
        assertNull(model.state.value.notice); assertNotNull(model.state.value.error)
        repository.fail = false; model.retry(); advanceUntilIdle(); val id = model.state.value.notice!!.id
        repository.fail = true; model.undoNotice(id); advanceUntilIdle()
        assertTrue(model.state.value.lists.getValue(ModerationListKind.Blocks).accounts.isEmpty())
        assertFalse(repository.relation.blocking); assertTrue(repository.relation.muting)
        repository.fail = false; model.retry(); advanceUntilIdle()
        assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Blocks).accounts.size)
        assertTrue(repository.relation.blocking)
    }
    @Test fun openingAndPagingCachedListsDoesNotFetchSettings() = runTest(dispatcher) {
        val repository = Repository()
        repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(mutes = (1..85).map { entry.copy(account = entry.account.copy(id = "account-" + it)) }) }
        val model = model(repository); advanceUntilIdle(); model.open(ModerationPage.Mutes); advanceUntilIdle()
        assertEquals(40, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        model.loadMore(); advanceUntilIdle(); assertEquals(80, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        assertTrue(repository.requests.isEmpty()); assertTrue(repository.syncs.isEmpty())
        model.refresh(); advanceUntilIdle(); assertEquals(setOf(ServerSetting.Mutes, ServerSetting.Blocks), repository.syncs.single())
    }
    @Test fun delayedUncancellableListDoesNotLeakAcrossAtoBtoA() = runTest(dispatcher) {
        val repository = Repository(); val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        repository.request = { _, _ -> withContext(NonCancellable) { gate.await() } }
        val model = model(repository); advanceUntilIdle(); model.open(ModerationPage.Mutes); runCurrent()
        model.selectAccount(secondAccount.sessionId); runCurrent()
        repository.request = { _, _ -> Result.success(ModerationAccountsPage(emptyList(), null)) }
        model.selectAccount(testAccount.sessionId); runCurrent(); gate.complete(Result.success(ModerationAccountsPage(listOf(entry), "stale"))); advanceUntilIdle()
        assertTrue(model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.isEmpty())
        assertNull(model.state.value.lists.getValue(ModerationListKind.Mutes).nextMaxId)
        assertFalse(model.state.value.busy)
    }
    @Test fun delayedUncancellableReleaseCannotChangeTheNewManagementGeneration() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); advanceUntilIdle()
        model.open(ModerationPage.Mutes); advanceUntilIdle()
        val gate = CompletableDeferred<Result<AccountRelationship>>(); repository.changeGate = gate
        model.release(entry); runCurrent()
        model.selectAccount(secondAccount.sessionId); runCurrent(); model.selectAccount(testAccount.sessionId); runCurrent()
        gate.complete(Result.success(entry.relationship.copy(muting = false))); advanceUntilIdle()
        assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        assertNull(model.state.value.notice); assertNull(model.state.value.error); assertFalse(model.state.value.busy)
    }
    @Test fun undoCancelsAConcurrentRefreshSoItsOldResponseCannotRemoveTheRestoredRow() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); advanceUntilIdle()
        model.open(ModerationPage.Mutes); advanceUntilIdle(); model.release(entry); advanceUntilIdle()
        val id = model.state.value.notice!!.id
        val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        repository.request = { _, _ -> withContext(NonCancellable) { gate.await() } }
        model.refresh(); runCurrent(); model.undoNotice(id); runCurrent()
        gate.complete(Result.success(ModerationAccountsPage(emptyList(), null))); advanceUntilIdle()
        assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        assertTrue(repository.relation.muting)
        assertFalse(model.state.value.lists.getValue(ModerationListKind.Mutes).loading)
    }
    @Test fun expiredMuteIsNotRecreatedByUndo() = runTest(dispatcher) {
        val expired = entry.copy(muteExpiresAt = "2026-10-04T00:00:00Z")
        val repository = Repository().also { repo -> repo.serverSettings.value = repo.serverSettings.value.mapValues { (_, saved) -> saved.copy(mutes = listOf(expired)) } }
        val model = model(repository); advanceUntilIdle(); model.open(ModerationPage.Mutes); advanceUntilIdle()
        model.release(expired); advanceUntilIdle(); model.undoNotice(model.state.value.notice!!.id); advanceUntilIdle()
        assertNull(repository.restored); assertFalse(repository.relation.muting)
        assertTrue(model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.isEmpty())
    }
    @Test fun accountReadFailureCanRetryWithoutLeavingTheScreen() = runTest(dispatcher) {
        var fail = true
        val flakyAuth = object : AuthRepository by auth {
            override suspend fun getSessions(): List<AccountSession> {
                if (fail) error("storage unavailable")
                return auth.getSessions()
            }
        }
        val model = own(ModerationManagementViewModel(Repository(), flakyAuth))
        advanceUntilIdle(); assertNotNull(model.state.value.error); assertNull(model.state.value.selected)
        fail = false; model.retry(); advanceUntilIdle()
        assertEquals(testAccount, model.state.value.selected); assertNull(model.state.value.error)
    }
}
