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
    @Test fun newQueryWinsEvenIfOldRequestIgnoresCancellation() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchResults>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String) =
                if (query == "old") withContext(NonCancellable) { delayed.await() } else super.search(session, query)
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
        delayed.complete(Result.success(testResults("old")))
        advanceUntilIdle()
        assertEquals("new", viewModel.uiState.value.searchResults?.statuses?.single()?.statusId)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test fun accountSwitchClearsQueryAndRejectsOldError() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<SearchResults>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun search(session: AccountSession, query: String) = withContext(NonCancellable) { delayed.await() }
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
        assertEquals(SearchUiState(), viewModel.uiState.value)
    }
}
