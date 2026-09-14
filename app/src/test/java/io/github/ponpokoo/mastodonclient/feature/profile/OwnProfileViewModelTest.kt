package io.github.ponpokoo.mastodonclient.feature.profile

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
class OwnProfileViewModelTest : ScreenViewModelTestBase() {
    @Test fun profileHeaderAppearsBeforeStatusesFinishLoading() = runTest(dispatcher) {
        val delayedStatuses = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                Result.success(testProfile().copy(statuses = emptyList(), pinnedStatuses = emptyList()))
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
                delayedStatuses.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.profile)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertTrue(viewModel.uiState.value.isLoadingMoreProfile)
        delayedStatuses.complete(Result.success(TimelinePage(listOf(testStatus()), null, true)))
        advanceUntilIdle()
        assertEquals(listOf("post"), viewModel.uiState.value.profile?.statuses?.map { it.statusId })
        assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
    }

    @Test fun latestTabWinsAndOldPaginationCannotAppendToIt() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<TimelinePage>>()
        val replies = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> = when {
                maxId != null -> withContext(NonCancellable) { oldPage.await() }
                tab == ProfileStatusTab.Replies -> withContext(NonCancellable) { replies.await() }
                else -> super.getProfileStatuses(session, accountId, tab, maxId)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.loadMoreProfile()
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Media)
        advanceUntilIdle()
        oldPage.complete(Result.success(TimelinePage(listOf(testStatus("old page")), null, true)))
        replies.complete(Result.failure(IllegalStateException("old tab")))
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Media, viewModel.uiState.value.profileSelectedTab)
        assertEquals(listOf("Media"), viewModel.uiState.value.profile?.statuses?.map { it.statusId })
        assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
        assertNull(viewModel.uiState.value.profileError)
    }

    @Test fun requestedProfileReloadsForNewAccountAndRejectsOldProfile() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<UserProfile>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfile(session: AccountSession, accountId: String) =
                if (session == testAccount) withContext(NonCancellable) { delayed.await() }
                else Result.success(testProfile().copy(noteHtml = "second"))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        delayed.complete(Result.success(testProfile().copy(noteHtml = "old")))
        advanceUntilIdle()
        assertEquals("second", viewModel.uiState.value.profile?.noteHtml)
    }
}
