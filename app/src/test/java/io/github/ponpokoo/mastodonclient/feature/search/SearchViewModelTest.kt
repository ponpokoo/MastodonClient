package io.github.ponpokoo.mastodonclient.feature.search

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : ScreenViewModelTestBase() {
    @Test fun editingAndChoosingTargetDoNotSearchUntilSubmitted() = runTest(dispatcher) {
        val requests = mutableListOf<Triple<SearchTarget, Int, Int>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int): Result<SearchPage> {
                requests += Triple(target, offset, limit)
                return Result.success(SearchPage(SearchResults(listOf(testStatus().author), emptyList(), emptyList()), null, true))
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        model.enterSearch()
        model.onQueryChanged("こんにちは")
        model.selectTarget(SearchTarget.Accounts)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        model.search()
        advanceUntilIdle()
        assertEquals(listOf(Triple(SearchTarget.Accounts, 0, 20)), requests)
        assertEquals(testStatus().author, model.uiState.value.searchResults!!.accounts.single())
    }

    @Test fun targetSwitchRejectsDelayedResponseAndReusesLoadedTarget() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchPage>>()
        val requests = mutableListOf<SearchTarget>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int): Result<SearchPage> {
                requests += target
                return if (target == SearchTarget.Posts && requests.size == 1) withContext(NonCancellable) { delayed.await() }
                else Result.success(SearchPage(SearchResults(emptyList(), emptyList(), listOf(SearchTag("tag", "https://example/tags/tag"))), null, true))
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        model.onQueryChanged("hello")
        model.search()
        advanceUntilIdle()
        model.selectTarget(SearchTarget.Hashtags)
        advanceUntilIdle()
        delayed.complete(Result.success(SearchPage(testResults("old"), null, true)))
        advanceUntilIdle()
        assertEquals("tag", model.uiState.value.searchResults!!.hashtags.single().name)
        assertNull(model.uiState.value.tabs[SearchTarget.Posts]?.results)
        model.selectTarget(SearchTarget.Accounts)
        advanceUntilIdle()
        model.selectTarget(SearchTarget.Hashtags)
        advanceUntilIdle()
        assertEquals(listOf(SearchTarget.Posts, SearchTarget.Hashtags, SearchTarget.Accounts), requests)
    }

    @Test fun paginationFailureKeepsResultsAndRetryUsesSameOffsetWithoutDuplicates() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int): Result<SearchPage> {
                offsets += offset
                return when (offsets.size) {
                    1 -> Result.success(SearchPage(testResults("first"), 1, false))
                    2 -> Result.failure(SearchException(SearchFailure.Timeout, java.net.SocketTimeoutException()))
                    3 -> Result.success(SearchPage(SearchResults(emptyList(), listOf(testStatus("first"), testStatus("second")), emptyList()), 3, false))
                    else -> Result.success(SearchPage(testResults("second"), 4, false))
                }
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        model.onQueryChanged("hello"); model.search(); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        assertEquals("first", model.uiState.value.searchResults!!.statuses.single().statusId)
        assertEquals(SearchFailure.Timeout, model.uiState.value.selectedTab.error)
        assertTrue(model.uiState.value.selectedTab.errorIsPagination)
        model.retry(); advanceUntilIdle()
        assertEquals(listOf("first", "second"), model.uiState.value.searchResults!!.statuses.map { it.statusId })
        assertNull(model.uiState.value.selectedTab.error)
        model.loadMore(); advanceUntilIdle()
        assertTrue(model.uiState.value.selectedTab.endReached)
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 1, 3), offsets)
    }

    @Test fun returnKeepsQueryAndClearRejectsPendingResult() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
                withContext(NonCancellable) { delayed.await() }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        model.onQueryChanged("へんかんちゅう")
        model.search(); advanceUntilIdle()
        model.returnToExplore()
        assertEquals("へんかんちゅう", model.uiState.value.searchQuery)
        assertFalse(model.uiState.value.isSearchActive)
        assertFalse(model.uiState.value.isSearching)
        model.enterSearch()
        model.returnToExplore(clear = true)
        delayed.complete(Result.success(SearchPage(testResults("old"), null, true)))
        advanceUntilIdle()
        assertEquals("", model.uiState.value.searchQuery)
        assertTrue(model.uiState.value.tabs.isEmpty())
        assertFalse(model.uiState.value.isSearchActive)
    }

    @Test fun changingQueryResetsEveryTargetsResultsAndPagination() = runTest(dispatcher) {
        val requests = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int): Result<SearchPage> {
                requests += "$query:$target:$offset"
                return Result.success(SearchPage(testResults(query), 20, false))
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        model.onQueryChanged("old"); model.search(); advanceUntilIdle()
        model.selectTarget(SearchTarget.Accounts); advanceUntilIdle()
        model.onQueryChanged("new")
        model.selectTarget(SearchTarget.Posts); model.loadMore(); advanceUntilIdle()
        assertTrue(model.uiState.value.tabs.isEmpty())
        model.search(); advanceUntilIdle()
        assertEquals(listOf("old:Posts:0", "old:Accounts:0", "new:Posts:0"), requests)
    }

    @Test fun newQueryWinsEvenIfOldRequestIgnoresCancellation() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
                if (query == "old") withContext(NonCancellable) { delayed.await() } else super.search(session, query, target, offset, limit)
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(SearchViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onQueryChanged("old")
        viewModel.search()
        advanceUntilIdle()
        viewModel.onQueryChanged("new")
        viewModel.search()
        advanceUntilIdle()
        delayed.complete(Result.success(SearchPage(testResults("old"), null, true)))
        advanceUntilIdle()
        assertEquals("new", viewModel.uiState.value.searchResults?.statuses?.single()?.statusId)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test fun accountSwitchClearsQueryAndRejectsOldError() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
                withContext(NonCancellable) { delayed.await() }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(SearchViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onQueryChanged("private query")
        viewModel.search()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        delayed.complete(Result.failure(IllegalStateException("old account")))
        advanceUntilIdle()
        assertEquals(SearchUiState(), viewModel.uiState.value.copy(sessionKey = null))
    }
}
