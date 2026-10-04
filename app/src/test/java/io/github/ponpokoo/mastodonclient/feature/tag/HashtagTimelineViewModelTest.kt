package io.github.ponpokoo.mastodonclient.feature.tag

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HashtagTimelineViewModelTest : ScreenViewModelTestBase() {
    private val tag = SearchTag("写真", "https://one.example/tags/photo", 456, false)
    private open inner class Repository : ScreenRepositoryFake() {
        override suspend fun getTag(session: AccountSession, name: String) = Result.success(tag)
        override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int) = Result.success(TimelinePage(listOf(testStatus()), null, true))
    }

    @Test fun subscribingFromDetailUpdatesExploreAndFailureRetainsConfirmedState() = runTest(dispatcher) {
        var actions = 0
        val repository = object : Repository() {
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) = Result.success(ExplorePage())
            override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean) =
                if (++actions == 1) Result.success(tag.copy(following = following)) else Result.failure(java.io.IOException())
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val explore = own(SearchViewModel(repository, browsing))
        val detail = own(HashtagTimelineViewModel("写真", repository, browsing))
        advanceUntilIdle(); explore.selectExploreFeed(ExploreFeed.Followed); advanceUntilIdle()
        detail.toggleSubscription(); advanceUntilIdle()
        assertEquals(true, detail.uiState.value.tag!!.following)
        assertEquals("写真", explore.exploreState.value.tabs[ExploreFeed.Followed]!!.page!!.tags.single().name)
        detail.toggleSubscription(); advanceUntilIdle()
        assertEquals(true, detail.uiState.value.tag!!.following)
        assertNotNull(detail.uiState.value.actionMessage)
        assertFalse(detail.uiState.value.isChangingSubscription)
    }

    @Test fun switchingAccountsRejectsDelayedTimelineAndSubscriptionAndDoesNotPublishOldTag() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<TimelinePage>>()
        val oldAction = CompletableDeferred<Result<SearchTag>>()
        var firstPage = true
        val repository = object : Repository() {
            override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int): Result<TimelinePage> {
                return if (firstPage.also { firstPage = false }) withContext(NonCancellable) { oldPage.await() }
                else Result.success(TimelinePage(listOf(testStatus("new")), null, true))
            }
            override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean) = withContext(NonCancellable) { oldAction.await() }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val detail = own(HashtagTimelineViewModel("写真", repository, browsing))
        advanceUntilIdle(); detail.toggleSubscription(); advanceUntilIdle()
        browsing.activate(secondAccount); advanceUntilIdle()
        oldPage.complete(Result.success(TimelinePage(listOf(testStatus("old")), null, true)))
        oldAction.complete(Result.success(tag.copy(following = true)))
        advanceUntilIdle()
        assertEquals("new", detail.uiState.value.statuses.single().statusId)
        assertEquals(false, detail.uiState.value.tag!!.following)
        assertFalse(detail.uiState.value.isChangingSubscription)
    }

    @Test fun unsupportedTagApiDoesNotPreventTimelineAndDuplicateSubscriptionTapsSendOneRequest() = runTest(dispatcher) {
        val action = CompletableDeferred<Result<SearchTag>>()
        var calls = 0
        var supported = false
        val repository = object : Repository() {
            override suspend fun getTag(session: AccountSession, name: String): Result<SearchTag> =
                if (supported) Result.success(tag) else Result.failure(UnsupportedOperationException())
            override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean): Result<SearchTag> {
                calls++; return action.await()
            }
        }
        val detail = own(HashtagTimelineViewModel("写真", repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); assertEquals("post", detail.uiState.value.statuses.single().statusId)
        assertNotNull(detail.uiState.value.subscriptionError)
        supported = true; detail.retrySubscription(); advanceUntilIdle()
        detail.toggleSubscription(); detail.toggleSubscription(); advanceUntilIdle()
        assertEquals(1, calls)
        action.complete(Result.success(tag.copy(following = true))); advanceUntilIdle()
        assertEquals(true, detail.uiState.value.tag!!.following)
    }
}
