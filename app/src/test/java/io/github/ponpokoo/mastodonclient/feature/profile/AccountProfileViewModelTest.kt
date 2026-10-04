package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.feature.common.ScreenRepositoryFake
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelTestBase
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountProfileViewModelTest : ScreenViewModelTestBase() {
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = testAccount
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun logout() = Unit
    }

    @Test fun cachedTabsReflectFavouriteAndDeletion() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?) = Result.success(TimelinePage(listOf(testStatus()), null, true))
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        viewModel.prepareTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        viewModel.toggleFavourite(testStatus())
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Replies)
        assertTrue(viewModel.uiState.value.profile!!.statuses.single().favourited)
        viewModel.deleteStatus(testStatus())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profileTabs.values.all { it.statuses.isEmpty() })
    }

    @Test fun preparingTabAndReturningToItKeepsItsPostsAndCursor() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<ProfileStatusTab, String?>>()
        var failRefresh = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?): Result<TimelinePage> {
                calls += tab to maxId
                if (failRefresh && tab == ProfileStatusTab.Replies) return Result.failure(IllegalStateException("refresh failed"))
                return Result.success(TimelinePage(listOf(testStatus(if (maxId == null) tab.name else "older")),
                    if (maxId == null) "next" else "older-cursor", false))
            }
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        viewModel.prepareTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Posts, viewModel.uiState.value.selectedTab)
        viewModel.selectTab(ProfileStatusTab.Replies)
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Posts)
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(3, calls.size)
        assertEquals(listOf("Replies", "older"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals("older-cursor", viewModel.uiState.value.profile!!.nextMaxId)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(4, calls.size)
        assertEquals(listOf("Replies"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertTrue(viewModel.uiState.value.profileTabs.getValue(ProfileStatusTab.Posts).isLoaded)
        failRefresh = true
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Posts)
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(5, calls.size)
        assertEquals(listOf("Replies"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test fun failedTabLoadDoesNotDisplayThePreviousTabsPostsOrCursor() = runTest(dispatcher) {
        val replies = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> =
                if (tab == ProfileStatusTab.Replies) replies.await()
                else super.getProfileStatuses(session, accountId, tab, maxId)
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.profile!!.statuses.isEmpty())
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertNull(viewModel.uiState.value.profile!!.nextMaxId)
        replies.complete(Result.failure(IllegalStateException("cannot load replies")))
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Replies, viewModel.uiState.value.selectedTab)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
    }
}
