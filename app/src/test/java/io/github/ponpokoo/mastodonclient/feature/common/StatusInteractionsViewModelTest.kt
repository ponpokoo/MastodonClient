package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusInteractionsViewModelTest : ScreenViewModelTestBase() {
    private class Repository : ScreenRepositoryFake() {
        val pending = mutableListOf<CompletableDeferred<Result<AccountListPage>>>()
        val cursors = mutableListOf<String?>()
        private suspend fun request(cursor: String?): Result<AccountListPage> = withContext(NonCancellable) {
            cursors += cursor
            CompletableDeferred<Result<AccountListPage>>().also { pending += it }.await()
        }
        override suspend fun getFavouritedBy(session: AccountSession, statusId: String, maxId: String?) = request(maxId)
        override suspend fun getRebloggedBy(session: AccountSession, statusId: String, maxId: String?) = request(maxId)
        override suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String, reactionName: String, maxId: String?) = request(maxId)
    }
    private fun reaction(ids: Set<String> = emptySet()) = EmojiReaction("smile", 2, false, null, ids)
    private fun page(vararg accounts: StatusAuthor, next: String? = null) = AccountListPage(accounts.toList(), next, next == null)
    private fun author(id: String) = testStatus().author.copy(id = id)
    private fun open(vm: StatusInteractionsViewModel, kind: Int) = when (kind) {
        0 -> vm.openFavourites("same")
        1 -> vm.openBoosters("same")
        else -> vm.openReaction("same", reaction())
    }

    @Test fun allListsRejectEarlierRequestsAndResponsesAfterClosing() = runTest(dispatcher) {
        for (kind in 0..2) {
            val repository = Repository()
            val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            runCurrent()
            fun state() = vm.accounts.value
            open(vm, kind); runCurrent(); vm.dismissAccounts(); open(vm, kind); runCurrent()
            repository.pending[1].complete(Result.success(page(testStatus("current").author))); runCurrent()
            repository.pending[0].complete(Result.success(page(testStatus().author.copy(id = "obsolete")))); runCurrent()
            assertEquals("me", state().accounts.single().id)
            open(vm, kind); runCurrent()
            vm.dismissAccounts()
            repository.pending[2].complete(Result.failure(IllegalStateException("late error"))); runCurrent()
            assertNull(state().request); assertNull(state().error); assertFalse(state().isLoading)
        }
    }

    @Test fun sameStatusAcrossAtoBtoARejectsOriginalAResult() = runTest(dispatcher) {
        for (kind in 0..2) {
            val repository = Repository(); val browsing = BrowsingSession().apply { activate(testAccount) }
            val vm = own(StatusInteractionsViewModel(repository, browsing)); runCurrent()
            open(vm, kind); runCurrent(); browsing.activate(secondAccount); runCurrent()
            browsing.activate(testAccount); runCurrent(); open(vm, kind); runCurrent()
            repository.pending[1].complete(Result.success(page(testStatus().author.copy(id = "new")))); runCurrent()
            repository.pending[0].complete(Result.success(page(testStatus().author.copy(id = "old")))); runCurrent()
            assertEquals("new", vm.accounts.value.accounts.single().id)
        }
    }

    @Test fun reactionFiltersExplicitAccountsAndUnfilteredReactionKeepsAll() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) })); runCurrent()
        val accounts = listOf(testStatus().author, testStatus().author.copy(id = "other"))
        vm.openReaction("status", reaction(setOf("other"))); runCurrent()
        repository.pending[0].complete(Result.success(AccountListPage(accounts, null, true))); runCurrent()
        assertEquals(listOf("other"), vm.accounts.value.accounts.map { it.id })
        vm.openReaction("status", reaction()); runCurrent()
        repository.pending[1].complete(Result.success(AccountListPage(accounts, null, true))); runCurrent()
        assertEquals(accounts, vm.accounts.value.accounts)
    }

    @Test fun failureEndsLoadingAndReopeningClearsError() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) })); runCurrent()
        vm.openFavourites("status"); runCurrent()
        repository.pending[0].complete(Result.failure(IllegalStateException("offline"))); runCurrent()
        assertEquals("offline", vm.accounts.value.error); assertFalse(vm.accounts.value.isLoading)
        vm.openFavourites("status"); runCurrent()
        assertNull(vm.accounts.value.error); assertTrue(vm.accounts.value.isLoading)
        repository.pending[1].complete(Result.success(page())); runCurrent()
        assertFalse(vm.accounts.value.isLoading)
    }

    @Test fun allListsAppendUsingServerCursorAndAvoidParallelRequestsAndDuplicates() = runTest(dispatcher) {
        for (kind in 0..2) {
            val repository = Repository()
            val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            runCurrent(); open(vm, kind); runCurrent()
            repository.pending[0].complete(Result.success(page(author("one"), author("two"), next = "opaque-cursor"))); runCurrent()
            assertEquals(1, repository.pending.size)
            vm.loadMoreAccounts(); vm.loadMoreAccounts(); runCurrent()
            assertEquals(listOf(null, "opaque-cursor"), repository.cursors)
            repository.pending[1].complete(Result.success(page(author("two"), author("three")))); runCurrent()
            assertEquals(listOf("one", "two", "three"), vm.accounts.value.accounts.map { it.id })
            assertTrue(vm.accounts.value.endReached)
            vm.loadMoreAccounts(); runCurrent()
            assertEquals(2, repository.pending.size)
        }
    }

    @Test fun filteredEmptyAndDuplicateReactionPagesContinueUntilNewAccountsArrive() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.openReaction("post", reaction(setOf("one", "two"))); runCurrent()
        repository.pending[0].complete(Result.success(page(author("unlisted"), next = "a"))); runCurrent()
        assertTrue(vm.accounts.value.isLoading)
        repository.pending[1].complete(Result.success(page(author("one"), next = "b"))); runCurrent()
        vm.loadMoreAccounts(); runCurrent()
        repository.pending[2].complete(Result.success(page(next = "c"))); runCurrent()
        repository.pending[3].complete(Result.success(page(author("one"), author("later-reactor"), next = "d"))); runCurrent()
        repository.pending[4].complete(Result.success(page(author("two")))); runCurrent()
        assertEquals(listOf(null, "a", "b", "c", "d"), repository.cursors)
        assertEquals(listOf("one", "two"), vm.accounts.value.accounts.map { it.id })
        assertTrue(vm.accounts.value.endReached)
    }

    @Test fun repeatedCursorCycleStopsEmptyPageLoading() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.openReaction("post", reaction()); runCurrent()
        repository.pending[0].complete(Result.success(page(next = "a"))); runCurrent()
        repository.pending[1].complete(Result.success(page(next = "b"))); runCurrent()
        repository.pending[2].complete(Result.success(page(next = "a"))); runCurrent()
        assertEquals(listOf(null, "a", "b"), repository.cursors)
        assertTrue(vm.accounts.value.endReached)
        assertFalse(vm.accounts.value.isLoading)
    }

    @Test fun paginationFailureKeepsAccountsAndRetryUsesTheFailedCursor() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.openBoosters("post"); runCurrent()
        repository.pending[0].complete(Result.success(page(author("one"), next = "cursor"))); runCurrent()
        vm.loadMoreAccounts(); runCurrent()
        repository.pending[1].complete(Result.failure(IllegalStateException("offline"))); runCurrent()
        assertEquals("one", vm.accounts.value.accounts.single().id)
        assertEquals("offline", vm.accounts.value.error)
        vm.loadMoreAccounts(); runCurrent()
        assertEquals(2, repository.pending.size)
        vm.retryAccounts(); runCurrent()
        assertNull(vm.accounts.value.error)
        assertEquals(listOf(null, "cursor", "cursor"), repository.cursors)
        repository.pending[2].complete(Result.success(page(author("two")))); runCurrent()
        assertEquals(listOf("one", "two"), vm.accounts.value.accounts.map { it.id })
    }

    @Test fun accountSwitchRejectsPendingAdditionalPageAfterReturningToSameAccount() = runTest(dispatcher) {
        val repository = Repository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(StatusInteractionsViewModel(repository, browsing)); runCurrent()
        vm.openBoosters("post"); runCurrent()
        repository.pending[0].complete(Result.success(page(author("first"), next = "cursor"))); runCurrent()
        vm.loadMoreAccounts(); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        assertNull(vm.accounts.value.request)
        browsing.activate(testAccount); runCurrent(); vm.openFavourites("post"); runCurrent()
        repository.pending[2].complete(Result.success(page(author("new")))); runCurrent()
        repository.pending[1].complete(Result.success(page(author("old")))); runCurrent()
        assertTrue(vm.accounts.value.request is StatusAccountsRequest.Favourites)
        assertEquals("new", vm.accounts.value.accounts.single().id)
    }
}
