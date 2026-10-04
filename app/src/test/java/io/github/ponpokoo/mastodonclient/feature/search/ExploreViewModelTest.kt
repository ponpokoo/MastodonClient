package io.github.ponpokoo.mastodonclient.feature.search

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest : ScreenViewModelTestBase() {
    private val tag = SearchTag("写真", "https://one.example/tags/photo", 456, true)

    @Test fun eachFeedLoadsOnDemandAndPreviouslyLoadedResultsAreReused() = runTest(dispatcher) {
        val requests = mutableListOf<ExploreFeed>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> {
                requests += feed; assertEquals(20, limit)
                return Result.success(ExplorePage(tags = listOf(tag)))
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); assertTrue(requests.isEmpty())
        model.ensureExploreLoaded(); advanceUntilIdle()
        model.selectExploreFeed(ExploreFeed.News); advanceUntilIdle()
        model.selectExploreFeed(ExploreFeed.Posts); advanceUntilIdle()
        assertEquals(listOf(ExploreFeed.Posts, ExploreFeed.News), requests)
        assertEquals(tag, model.exploreState.value.tabs[ExploreFeed.Posts]!!.page!!.tags.single())
    }

    @Test fun failedPaginationRetainsContentAndRetriesSameCursor() = runTest(dispatcher) {
        val cursors = mutableListOf<String?>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> {
                cursors += cursor
                return when (cursors.size) {
                    1 -> Result.success(ExplorePage(tags = listOf(tag), nextCursor = "relationship-next"))
                    2 -> Result.failure(SearchException(SearchFailure.Timeout, java.net.SocketTimeoutException()))
                    else -> Result.success(ExplorePage(tags = listOf(tag, tag.copy(name = "音楽"))))
                }
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.selectExploreFeed(ExploreFeed.Followed); advanceUntilIdle()
        model.loadMoreExplore(); advanceUntilIdle()
        assertEquals(listOf(tag), model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags)
        assertNotNull(model.exploreState.value.tabs[ExploreFeed.Followed]!!.error)
        model.retryExplore(); advanceUntilIdle()
        assertEquals(listOf(null, "relationship-next", "relationship-next"), cursors)
        assertEquals(2, model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags.size)
    }

    @Test fun refreshAndAccountSwitchRejectCancelledResponsesIncludingReturnToSameAccount() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<ExplorePage>>()
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> {
                return if (++calls == 1) withContext(NonCancellable) { delayed.await() }
                else Result.success(ExplorePage(statuses = listOf(testStatus("new"))))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SearchViewModel(repository, browsing))
        advanceUntilIdle(); model.ensureExploreLoaded(); advanceUntilIdle()
        browsing.activate(secondAccount); advanceUntilIdle()
        browsing.activate(testAccount); advanceUntilIdle()
        model.ensureExploreLoaded(); advanceUntilIdle()
        delayed.complete(Result.success(ExplorePage(statuses = listOf(testStatus("old")))))
        advanceUntilIdle()
        assertEquals("new", model.exploreState.value.tabs[ExploreFeed.Posts]!!.page!!.statuses.single().statusId)
    }

    @Test fun unfollowFailureKeepsRowAndSuccessUpdatesAllListsAndInFlightResponses() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<ExplorePage>>()
        var actions = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) =
                if (feed == ExploreFeed.Hashtags) withContext(NonCancellable) { delayed.await() }
                else Result.success(ExplorePage(tags = listOf(tag)))
            override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean): Result<SearchTag> {
                return if (++actions == 1) Result.failure(java.io.IOException()) else Result.success(tag.copy(following = following))
            }
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.selectExploreFeed(ExploreFeed.Followed); advanceUntilIdle()
        model.setTagFollowing(tag, false); advanceUntilIdle()
        assertEquals(listOf(tag), model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags)
        assertNotNull(model.exploreState.value.actionMessage)
        model.selectExploreFeed(ExploreFeed.Hashtags); advanceUntilIdle()
        model.setTagFollowing(tag, false); advanceUntilIdle()
        delayed.complete(Result.success(ExplorePage(tags = listOf(tag)))); advanceUntilIdle()
        assertTrue(model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags.isEmpty())
        assertEquals(false, model.exploreState.value.tabs[ExploreFeed.Hashtags]!!.page!!.tags.single().following)
        assertTrue(model.exploreState.value.busyTags.isEmpty())
    }

    @Test fun statusActionsAndDeletionUpdateTrendingPosts() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) = Result.success(ExplorePage(statuses = listOf(testStatus())))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SearchViewModel(repository, browsing))
        advanceUntilIdle(); model.ensureExploreLoaded(); advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(testStatus().copy(favourited = true)))
        advanceUntilIdle(); assertTrue(model.exploreState.value.tabs[ExploreFeed.Posts]!!.page!!.statuses.single().favourited)
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("post"))
        advanceUntilIdle(); assertTrue(model.exploreState.value.tabs[ExploreFeed.Posts]!!.page!!.statuses.isEmpty())
    }

    @Test fun subscriptionConfirmedDuringRefreshIsKeptAndLaterRefreshAdoptsServerState() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<ExplorePage>>()
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> {
                return if (++calls == 2) delayed.await() else Result.success(ExplorePage())
            }
            override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean) = Result.success(tag.copy(following = following))
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.selectExploreFeed(ExploreFeed.Followed); advanceUntilIdle()
        model.refreshExplore(); advanceUntilIdle()
        model.setTagFollowing(tag, true); advanceUntilIdle()
        delayed.complete(Result.success(ExplorePage())); advanceUntilIdle()
        assertEquals(listOf(tag), model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags)
        // A later server refresh may reflect an unfollow made from another client.
        model.refreshExplore(); advanceUntilIdle()
        assertTrue(model.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags.isEmpty())
    }

    @Test fun refreshingSupersedesEarlierRequestWithoutRevertingFreshResults() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<ExplorePage>>()
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int): Result<ExplorePage> =
                if (++calls == 1) withContext(NonCancellable) { delayed.await() } else Result.success(ExplorePage(statuses = listOf(testStatus("new"))))
        }
        val model = own(SearchViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.ensureExploreLoaded(); advanceUntilIdle()
        model.refreshExplore(); advanceUntilIdle()
        delayed.complete(Result.success(ExplorePage(statuses = listOf(testStatus("old"))))); advanceUntilIdle()
        assertEquals("new", model.exploreState.value.tabs[ExploreFeed.Posts]!!.page!!.statuses.single().statusId)
    }
}
