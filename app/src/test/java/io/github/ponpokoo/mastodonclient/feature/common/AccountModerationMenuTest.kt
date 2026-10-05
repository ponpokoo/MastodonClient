package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountModerationMenuTest {
    @Test fun reopeningReusesFreshStateButExpiryAndContextChangesReloadIt() = runTest {
        var now = 0L
        var context = 1
        var calls = 0
        val response = CompletableDeferred<Result<AccountRelationship>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> {
                calls++
                return if (calls == 1) Result.success(AccountRelationship(muting = true)) else response.await()
            }
        }
        val menu = AccountModerationMenu(repository, this, { testAccount }, { context }, message = {}, nanoTime = { now })
        menu.load("author"); advanceUntilIdle()
        now = 59_000_000_000L
        menu.load("author")
        assertFalse(menu.state.value.loading)
        assertTrue(menu.state.value.relationship!!.muting)
        assertEquals(1, calls)
        now = 60_000_000_000L
        menu.load("author"); runCurrent()
        menu.load("author"); runCurrent()
        assertTrue(menu.state.value.loading)
        assertEquals(2, calls)
        response.complete(Result.success(AccountRelationship()))
        advanceUntilIdle()
        context++
        menu.load("author"); advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test fun profileRelationshipAndSuccessfulMutationAreReusedWhenReopening() = runTest {
        var lookups = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> {
                lookups++
                return Result.success(AccountRelationship())
            }
            override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean) =
                Result.success(AccountRelationship(muting = muted))
        }
        val menu = AccountModerationMenu(repository, this, { testAccount }, { 1 }, message = {})
        menu.remember("author", AccountRelationship())
        menu.load("author"); advanceUntilIdle()
        menu.mute("author", true); advanceUntilIdle()
        menu.load("author"); advanceUntilIdle()
        assertEquals(0, lookups)
        assertTrue(menu.state.value.relationship!!.muting)
        menu.reset()
        menu.load("author"); advanceUntilIdle()
        assertEquals(1, lookups)
    }

    @Test fun loadsExistingStateAndUnmutesWithoutChangingBlock() = runTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val messages = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String) =
                Result.success(AccountRelationship(muting = true, blocking = true))
            override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
                calls += accountId to muted
                return Result.success(AccountRelationship(muting = muted, blocking = true))
            }
        }
        val menu = AccountModerationMenu(repository, this, { testAccount }, { 1 }, message = messages::add)
        menu.load("opaque-author")
        advanceUntilIdle()
        assertTrue(menu.state.value.relationship!!.muting)
        menu.mute("opaque-author", false)
        advanceUntilIdle()
        assertEquals(listOf("opaque-author" to false), calls)
        assertFalse(menu.state.value.relationship!!.muting)
        assertTrue(menu.state.value.relationship!!.blocking)
        assertEquals(1, messages.size)
    }

    @Test fun failedLookupCannotMutateAndRetryEnablesTheAction() = runTest {
        var fail = true
        var mutations = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String) =
                if (fail) Result.failure(IllegalStateException("offline")) else Result.success(AccountRelationship())
            override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> {
                mutations++
                return Result.success(AccountRelationship(blocking = blocked))
            }
        }
        val menu = AccountModerationMenu(repository, this, { testAccount }, { 1 }, message = {})
        menu.load("author"); advanceUntilIdle()
        assertNotNull(menu.state.value.error)
        menu.block("author", true); advanceUntilIdle()
        assertEquals(0, mutations)
        fail = false
        menu.load("author"); advanceUntilIdle()
        menu.block("author", true); advanceUntilIdle()
        assertEquals(1, mutations)
        assertTrue(menu.state.value.relationship!!.blocking)
    }

    @Test fun failedUnblockRetainsStateAndDuplicateRequestsAreIgnored() = runTest {
        val response = CompletableDeferred<Result<AccountRelationship>>()
        var calls = 0
        val messages = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String) = Result.success(AccountRelationship(blocking = true))
            override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> {
                calls++
                return response.await()
            }
        }
        val menu = AccountModerationMenu(repository, this, { testAccount }, { 1 }, message = messages::add)
        menu.load("author"); advanceUntilIdle()
        menu.block("author", false); runCurrent()
        menu.block("author", false); runCurrent()
        assertTrue(menu.state.value.busy)
        response.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(menu.state.value.relationship!!.blocking)
        assertFalse(menu.state.value.busy)
        assertEquals(listOf("offline"), messages)
    }

    @Test fun supersededLookupAndCancelledMutationCannotUpdateANewSession() = runTest {
        var account = testAccount
        var generation = 1
        val lateLookup = CompletableDeferred<AccountRelationship>()
        val lateMutation = CompletableDeferred<AccountRelationship>()
        val messages = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getRelationship(session: AccountSession, accountId: String) =
                Result.success(if (accountId == "old") withContext(NonCancellable) { lateLookup.await() } else AccountRelationship())
            override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean) =
                Result.success(withContext(NonCancellable) { lateMutation.await() })
        }
        val menu = AccountModerationMenu(repository, this, { account }, { generation }, message = messages::add)
        menu.load("old"); runCurrent()
        menu.load("new"); runCurrent()
        lateLookup.complete(AccountRelationship(muting = true)); advanceUntilIdle()
        assertEquals("new", menu.state.value.accountId)
        assertFalse(menu.state.value.relationship!!.muting)
        menu.mute("new", true); runCurrent()
        account = secondAccount; generation++
        menu.reset(); menu.load("new"); runCurrent()
        account = testAccount; generation++
        menu.reset(); menu.load("new"); runCurrent()
        lateMutation.complete(AccountRelationship(muting = true)); advanceUntilIdle()
        assertFalse(menu.state.value.relationship!!.muting)
        assertTrue(messages.isEmpty())
    }
}
