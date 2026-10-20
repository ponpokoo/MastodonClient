package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ProfileFeaturedTagsViewModelTest(private val ownProfile: Boolean) : ScreenViewModelTestBase() {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "ownProfile={0}")
        fun profiles() = listOf(arrayOf(true), arrayOf(false))
    }

    private open class Repository : ScreenRepositoryFake() {
        var tags: Result<List<FeaturedTag>> = Result.success(listOf(FeaturedTag("opaque-a", "写真"), FeaturedTag("opaque-b", "art")))
        val taggedRequests = mutableListOf<Triple<ProfileStatusTab, String, String?>>()
        override suspend fun getFeaturedTags(session: AccountSession, accountId: String) = tags
        override suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String) = Result.success(listOf(testStatus("pin")))
        override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
            tab: ProfileStatusTab, hashtag: String, maxId: String?): Result<TimelinePage> {
            taggedRequests += Triple(tab, hashtag, maxId)
            return Result.success(TimelinePage(listOf(testStatus(hashtag)), "older", false))
        }
    }

    private class Model(
        val state: () -> ProfileUiState,
        val select: (String) -> Unit,
        val tab: (ProfileStatusTab) -> Unit,
        val refreshProfile: () -> Unit,
        val retryProfile: () -> Unit,
        val more: () -> Unit,
        val autoMore: () -> Unit,
        val retryTags: () -> Unit,
    )

    private fun create(repository: Repository, browsing: BrowsingSession = BrowsingSession().apply { activate(testAccount) }): Model {
        val vm = own(ProfileViewModel(repository, browsing, accountId = if (ownProfile) null else "author"))
        return Model({ vm.uiState.value }, vm::selectHashtag, vm::selectProfileTab,
            { if (vm.uiState.value.profile == null) vm.loadProfile() else vm.refreshProfile() },
            vm::retryProfile, vm::loadMoreProfile, vm::loadMoreProfileAutomatically, vm::retryFeaturedTags)
    }

    @Test fun singleSelectionSwitchesAndTogglesOffWithoutKeepingPinnedRows() = runTest(dispatcher) {
        val repository = Repository()
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        assertEquals(listOf("写真", "art"), model.state().featuredTags.map { it.name })
        assertEquals("pin", model.state().profile!!.pinnedStatuses.single().statusId)
        model.select("写真"); advanceUntilIdle()
        assertEquals("写真", model.state().selectedHashtag)
        assertTrue(model.state().profile!!.pinnedStatuses.isEmpty())
        assertEquals(listOf("写真"), model.state().profile!!.statuses.map { it.statusId })
        model.select("art"); advanceUntilIdle()
        assertEquals("art", model.state().selectedHashtag)
        assertEquals(listOf("art"), model.state().profile!!.statuses.map { it.statusId })
        model.select("art"); advanceUntilIdle()
        assertNull(model.state().selectedHashtag)
        assertEquals("post", model.state().profile!!.statuses.single().statusId)
        assertEquals("pin", model.state().profile!!.pinnedStatuses.single().statusId)
    }

    @Test fun allTabsCanFilterAndSwitchingTabsOrRefreshingClearsSelection() = runTest(dispatcher) {
        val repository = Repository()
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        for (tab in ProfileStatusTab.entries) {
            model.tab(tab); advanceUntilIdle()
            assertNull(model.state().selectedHashtag)
            model.select("写真"); advanceUntilIdle()
            assertEquals(Triple(tab, "写真", null), repository.taggedRequests.last())
        }
        model.refreshProfile(); advanceUntilIdle()
        assertNull(model.state().selectedHashtag)
        assertEquals("Media", model.state().profile!!.statuses.single().statusId)
    }

    @Test fun switchingFiltersRejectsLateResponsesEvenWhenCancellationIsIgnored() = runTest(dispatcher) {
        val old = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) =
                if (hashtag == "写真") withContext(NonCancellable) { old.await() }
                else super.getTaggedProfileStatuses(session, accountId, tab, hashtag, maxId)
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); runCurrent()
        advanceTimeBy(9_000)
        model.select("art"); advanceUntilIdle()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        old.complete(Result.success(TimelinePage(listOf(testStatus("stale")), null, true)))
        advanceUntilIdle()
        assertEquals("art", model.state().selectedHashtag)
        assertEquals(listOf("art"), model.state().profile!!.statuses.map { it.statusId })
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
    }

    @Test fun slowTaggedFirstPageShowsNoticeAtTenSecondsAndClearsItOnSuccess() = runTest(dispatcher) {
        val finish = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) = finish.await()
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        advanceTimeBy(1); runCurrent()
        assertTrue(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        finish.complete(Result.success(TimelinePage(listOf(testStatus("tagged")), null, true)))
        advanceUntilIdle()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        assertEquals("tagged", model.state().profile!!.statuses.single().statusId)
    }

    @Test fun slowTaggedPaginationClearsNoticeOnFailureAndRestartsDelayOnRetry() = runTest(dispatcher) {
        var finish = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) = if (maxId == null)
                super.getTaggedProfileStatuses(session, accountId, tab, hashtag, maxId) else finish.await()
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); advanceUntilIdle()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        model.more(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        finish.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        assertEquals(listOf("写真"), model.state().profile!!.statuses.map { it.statusId })
        finish = CompletableDeferred()
        model.retryProfile(); runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        advanceTimeBy(1); runCurrent()
        assertTrue(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        finish.complete(Result.success(TimelinePage(listOf(testStatus("older")), null, true)))
        advanceUntilIdle()
        assertFalse(model.state().profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
        assertEquals(listOf("写真", "older"), model.state().profile!!.statuses.map { it.statusId })
    }

    @Test fun failedTaggedPaginationRetriesSameFilterAndCursorAndStopsAutomaticRequests() = runTest(dispatcher) {
        var attempts = 0
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?): Result<TimelinePage> {
                val page = super.getTaggedProfileStatuses(session, accountId, tab, hashtag, maxId)
                if (maxId == null) return page
                return if (++attempts == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(TimelinePage(listOf(testStatus("older")), null, true))
            }
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); advanceUntilIdle()
        model.more(); advanceUntilIdle()
        model.autoMore(); advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals(listOf("写真"), model.state().profile!!.statuses.map { it.statusId })
        model.retryProfile(); advanceUntilIdle()
        assertEquals("写真", model.state().selectedHashtag)
        assertEquals(listOf("写真", "older"), model.state().profile!!.statuses.map { it.statusId })
        assertEquals(listOf(null, "older", "older"), repository.taggedRequests.map { it.third })
    }

    @Test fun tagFailureIsIndependentAndRefreshRetainsPreviouslyLoadedTags() = runTest(dispatcher) {
        val finish = CompletableDeferred<Result<List<FeaturedTag>>>()
        var pending = true
        val repository = object : Repository() {
            override suspend fun getFeaturedTags(session: AccountSession, accountId: String) =
                if (pending) finish.await() else tags
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        assertNotNull(model.state().profile)
        assertEquals("post", model.state().profile!!.statuses.single().statusId)
        finish.complete(Result.failure(IllegalStateException("offline"))); advanceUntilIdle()
        assertNotNull(model.state().featuredTagsError)
        pending = false
        model.retryTags(); advanceUntilIdle()
        val previous = model.state().featuredTags
        assertFalse(previous.isEmpty())
        assertNull(model.state().featuredTagsError)
        repository.tags = Result.failure(IllegalStateException("offline"))
        model.refreshProfile(); advanceUntilIdle()
        assertEquals(previous, model.state().featuredTags)
        assertNotNull(model.state().featuredTagsError)
        repository.tags = Result.success(emptyList())
        model.retryTags(); advanceUntilIdle()
        assertTrue(model.state().featuredTags.isEmpty())
        assertNull(model.state().featuredTagsError)
    }

    @Test fun refreshClearsFilterAndRejectsItsPendingResponse() = runTest(dispatcher) {
        val finish = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) = withContext(NonCancellable) { finish.await() }
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); advanceUntilIdle()
        model.refreshProfile(); advanceUntilIdle()
        assertNull(model.state().selectedHashtag)
        assertEquals("post", model.state().profile!!.statuses.single().statusId)
        finish.complete(Result.success(TimelinePage(listOf(testStatus("stale")), null, true)))
        advanceUntilIdle()
        assertEquals("post", model.state().profile!!.statuses.single().statusId)
        assertEquals("pin", model.state().profile!!.pinnedStatuses.single().statusId)
    }

    @Test fun failedFirstFilteredPageRetriesWithoutClearingTheSelectedTag() = runTest(dispatcher) {
        var attempts = 0
        val repository = object : Repository() {
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) =
                if (++attempts == 1) Result.failure(IllegalStateException("offline"))
                else super.getTaggedProfileStatuses(session, accountId, tab, hashtag, maxId)
        }
        val model = create(repository)
        advanceUntilIdle(); if (ownProfile) model.refreshProfile(); advanceUntilIdle()
        model.select("写真"); advanceUntilIdle()
        assertNotNull(model.state().profileTabs.getValue(ProfileStatusTab.Posts).error)
        assertTrue(model.state().profile!!.statuses.isEmpty())
        model.retryProfile(); advanceUntilIdle()
        assertEquals("写真", model.state().selectedHashtag)
        assertEquals("写真", model.state().profile!!.statuses.single().statusId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OwnProfileFeaturedTagsSessionTest : ScreenViewModelTestBase() {
    @Test fun accountSwitchRejectsPendingTagsAndPosts() = runTest(dispatcher) {
        val oldTags = CompletableDeferred<Result<List<FeaturedTag>>>()
        val oldPosts = CompletableDeferred<Result<TimelinePage>>()
        var pending = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getFeaturedTags(session: AccountSession, accountId: String) =
                if (pending && session == testAccount) withContext(NonCancellable) { oldTags.await() }
                else Result.success(listOf(FeaturedTag("one", "写真")))
            override suspend fun getTaggedProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, hashtag: String, maxId: String?) = withContext(NonCancellable) { oldPosts.await() }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(ProfileViewModel(repository, browsing))
        advanceUntilIdle(); model.loadProfile(); advanceUntilIdle()
        pending = true
        model.retryFeaturedTags(); model.selectHashtag("写真"); advanceUntilIdle()
        browsing.activate(secondAccount); advanceUntilIdle()
        oldTags.complete(Result.success(listOf(FeaturedTag("stale", "stale"))))
        oldPosts.complete(Result.success(TimelinePage(listOf(testStatus("stale")), null, true)))
        advanceUntilIdle()
        assertNull(model.uiState.value.selectedHashtag)
        assertEquals(listOf("写真"), model.uiState.value.featuredTags.map { it.name })
        assertEquals("post", model.uiState.value.profile!!.statuses.single().statusId)
        assertFalse(model.uiState.value.profileTabs.getValue(ProfileStatusTab.Posts).isSlowLoading)
    }
}
