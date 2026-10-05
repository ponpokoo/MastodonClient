package io.github.ponpokoo.mastodonclient.feature.settings

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationManagementViewModelTest : ScreenViewModelTestBase() {
    private val entry = ModerationAccount(testStatus().author, AccountRelationship(muting = true, blocking = true, mutingNotifications = false), "2026-10-05T00:01:00Z")
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = testAccount
        override suspend fun getSessions() = listOf(testAccount, secondAccount)
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
    }
    private class Words : WordMuteRepository {
        override val words = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        override suspend fun add(sessionId: String, word: String) { words.value = words.value + (sessionId to (words.value[sessionId].orEmpty() + word)) }
        override suspend fun remove(sessionId: String, word: String) { words.value = words.value + (sessionId to (words.value[sessionId].orEmpty() - word)) }
        override suspend fun restore(sessionId: String, word: String, index: Int) { words.value = words.value + (sessionId to words.value[sessionId].orEmpty().toMutableList().apply { add(index.coerceIn(0, size), word) }) }
    }
    private inner class Repository : ScreenRepositoryFake() {
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
    private fun model(repository: Repository, words: Words = Words()) = own(ModerationManagementViewModel(repository, auth, words) { Instant.parse("2026-10-05T00:00:00Z") })

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
    @Test fun wordsAreAccountScopedAndExpiredOrSwitchedNoticeCannotUndo() = runTest(dispatcher) {
        val words = Words(); val model = model(Repository(), words); advanceUntilIdle()
        model.open(ModerationPage.Words); model.addWord("ネタバレ"); advanceUntilIdle()
        val oldNotice = model.state.value.notice!!.id
        model.selectAccount(secondAccount.sessionId); advanceUntilIdle(); model.undoNotice(oldNotice); advanceUntilIdle()
        assertTrue(model.state.value.words.isEmpty())
        model.selectAccount(testAccount.sessionId); advanceUntilIdle(); assertEquals(listOf("ネタバレ"), model.state.value.words)
        model.removeWord("ネタバレ"); advanceUntilIdle(); model.undoNotice(model.state.value.notice!!.id); advanceUntilIdle()
        assertEquals(listOf("ネタバレ"), model.state.value.words)
        model.addWord("期限"); advanceUntilIdle(); val expired = model.state.value.notice!!.id
        model.consumeNotice(expired); model.undoNotice(expired); advanceUntilIdle()
        assertEquals(listOf("ネタバレ", "期限"), model.state.value.words)
    }
    @Test fun paginationUsesOpaqueCursorDeduplicatesAndRetainsRowsOnFailure() = runTest(dispatcher) {
        val repository = Repository(); var fail = true
        repository.request = { _, cursor -> if (cursor == null) Result.success(ModerationAccountsPage(listOf(entry), "opaque-cursor"))
            else if (fail) Result.failure(IllegalStateException("offline")) else Result.success(ModerationAccountsPage(listOf(entry, entry.copy(account = entry.account.copy(id = "second"))), null)) }
        val model = model(repository); advanceUntilIdle(); model.open(ModerationPage.Mutes); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle(); assertEquals(1, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        fail = false; model.retry(); advanceUntilIdle()
        assertEquals(listOf(null, "opaque-cursor", "opaque-cursor"), repository.requests.map { it.second })
        assertEquals(2, model.state.value.lists.getValue(ModerationListKind.Mutes).accounts.size)
        assertNull(model.state.value.lists.getValue(ModerationListKind.Mutes).nextMaxId)
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
        val repository = Repository().also { it.request = { _, _ -> Result.success(ModerationAccountsPage(listOf(expired), null)) } }
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
        val model = own(ModerationManagementViewModel(Repository(), flakyAuth, Words()))
        advanceUntilIdle(); assertNotNull(model.state.value.error); assertNull(model.state.value.selected)
        fail = false; model.retry(); advanceUntilIdle()
        assertEquals(testAccount, model.state.value.selected); assertNull(model.state.value.error)
    }
}
