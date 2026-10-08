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
        val pending = mutableListOf<CompletableDeferred<Result<List<StatusAuthor>>>>()
        private suspend fun request(): Result<List<StatusAuthor>> = withContext(NonCancellable) {
            CompletableDeferred<Result<List<StatusAuthor>>>().also { pending += it }.await()
        }
        override suspend fun getFavouritedBy(session: AccountSession, statusId: String) = request()
        override suspend fun getEmojiReactionedBy(session: AccountSession, statusId: String, reactionName: String) = request()
    }
    private fun reaction(ids: Set<String> = emptySet()) = EmojiReaction("smile", 2, false, null, ids)

    @Test fun bothListsRejectEarlierRequestsAndResponsesAfterClosing() = runTest(dispatcher) {
        for (isReaction in listOf(false, true)) {
            val repository = Repository()
            val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            runCurrent()
            fun open(id: String) { if (isReaction) vm.openReaction(id, reaction()) else vm.openFavourites(id) }
            fun state() = if (isReaction) vm.reactions.value else vm.favourites.value
            open("first"); runCurrent(); open("second"); runCurrent()
            repository.pending[1].complete(Result.success(listOf(testStatus("current").author))); runCurrent()
            repository.pending[0].complete(Result.success(listOf(testStatus().author.copy(id = "obsolete")))); runCurrent()
            assertEquals("me", state().accounts.single().id)
            open("third"); runCurrent()
            if (isReaction) vm.dismissReactions() else vm.dismissFavourites()
            repository.pending[2].complete(Result.failure(IllegalStateException("late error"))); runCurrent()
            assertNull(state().request); assertNull(state().error); assertFalse(state().isLoading)
        }
    }

    @Test fun sameStatusAcrossAtoBtoARejectsOriginalAResult() = runTest(dispatcher) {
        for (isReaction in listOf(false, true)) {
            val repository = Repository(); val browsing = BrowsingSession().apply { activate(testAccount) }
            val vm = own(StatusInteractionsViewModel(repository, browsing)); runCurrent()
            fun open() { if (isReaction) vm.openReaction("same", reaction()) else vm.openFavourites("same") }
            open(); runCurrent(); browsing.activate(secondAccount); runCurrent()
            browsing.activate(testAccount); runCurrent(); open(); runCurrent()
            repository.pending[1].complete(Result.success(listOf(testStatus().author.copy(id = "new")))); runCurrent()
            repository.pending[0].complete(Result.success(listOf(testStatus().author.copy(id = "old")))); runCurrent()
            assertEquals("new", (if (isReaction) vm.reactions else vm.favourites).value.accounts.single().id)
        }
    }

    @Test fun reactionFiltersExplicitAccountsAndUnfilteredReactionKeepsAll() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) })); runCurrent()
        val accounts = listOf(testStatus().author, testStatus().author.copy(id = "other"))
        vm.openReaction("status", reaction(setOf("other"))); runCurrent()
        repository.pending[0].complete(Result.success(accounts)); runCurrent()
        assertEquals(listOf("other"), vm.reactions.value.accounts.map { it.id })
        vm.openReaction("status", reaction()); runCurrent()
        repository.pending[1].complete(Result.success(accounts)); runCurrent()
        assertEquals(accounts, vm.reactions.value.accounts)
    }

    @Test fun failureEndsLoadingAndReopeningClearsError() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(StatusInteractionsViewModel(repository, BrowsingSession().apply { activate(testAccount) })); runCurrent()
        vm.openFavourites("status"); runCurrent()
        repository.pending[0].complete(Result.failure(IllegalStateException("offline"))); runCurrent()
        assertEquals("offline", vm.favourites.value.error); assertFalse(vm.favourites.value.isLoading)
        vm.openFavourites("status"); runCurrent()
        assertNull(vm.favourites.value.error); assertTrue(vm.favourites.value.isLoading)
        repository.pending[1].complete(Result.success(emptyList())); runCurrent()
        assertFalse(vm.favourites.value.isLoading)
    }
}
