package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedProfileViewModelTest : ScreenViewModelTestBase() {
    private open class Repository : ScreenRepositoryFake() {
        override suspend fun getProfileHeader(session: AccountSession, accountId: String) = Result.success(
            testProfile().copy(author = testStatus().author.copy(id = accountId), statuses = emptyList(),
                isOwnProfile = accountId == session.accountId, noteHtml = session.instanceUrl))
        override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest) =
            getProfileHeader(session, session.accountId).map { it.copy(noteHtml = request.note,
                headerUrl = "https://images.example/edited", author = it.author.copy(displayName = request.displayName)) }
    }

    @Test fun entriesKeepIndependentTabsAndReceiveTheSameEditedProfile() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val repository = Repository()
        val main = own(ProfileViewModel(repository, browsing))
        val separate = own(ProfileViewModel(repository, browsing, accountId = testAccount.accountId))
        advanceUntilIdle(); main.loadProfile(); advanceUntilIdle()
        main.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Posts, separate.uiState.value.profileSelectedTab)
        val mainTabs = main.uiState.value.profileTabs
        val separateTabs = separate.uiState.value.profileTabs
        separate.updateProfile(ProfileEditRequest("Changed", "edited biography", false, false))
        advanceUntilIdle()
        for (model in listOf(main, separate)) {
            assertEquals("Changed", model.uiState.value.profile!!.author.displayName)
            assertEquals("edited biography", model.uiState.value.profile!!.noteHtml)
            assertEquals("https://images.example/edited", model.uiState.value.profile!!.headerUrl)
        }
        assertEquals(ProfileStatusTab.Replies, main.uiState.value.profileSelectedTab)
        assertEquals(mainTabs, main.uiState.value.profileTabs)
        assertEquals(separateTabs, separate.uiState.value.profileTabs)
    }

    @Test fun explicitAccountIdsNeverTravelToAnotherServerAndLateRoundTripResponsesAreRejected() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val delayed = CompletableDeferred<Result<UserProfile>>()
        val requests = mutableListOf<Pair<String, String>>()
        var delayRefresh = false
        val repository = object : Repository() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String): Result<UserProfile> {
                requests += session.instanceUrl to accountId
                return if (delayRefresh) withContext(NonCancellable) { delayed.await() }
                else super.getProfileHeader(session, accountId)
            }
        }
        val model = own(ProfileViewModel(repository, browsing, accountId = "opaque-target"))
        advanceUntilIdle()
        delayRefresh = true
        model.refreshProfile(); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        assertNull(model.uiState.value.profile)
        model.retryProfile(); runCurrent()
        assertEquals(2, requests.size)
        delayRefresh = false
        browsing.activate(testAccount); runCurrent()
        val restored = model.uiState.value.profile
        delayed.complete(Result.success(testProfile().copy(noteHtml = "obsolete")))
        advanceUntilIdle()
        assertEquals(restored, model.uiState.value.profile)
        assertEquals(testAccount.instanceUrl, model.uiState.value.profile!!.noteHtml)
        assertTrue(requests.all { it.first == testAccount.instanceUrl && it.second == "opaque-target" })
    }

    @Test fun lateProfileEditCannotChangeEitherEntryAfterAnAccountSwitch() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val delayed = CompletableDeferred<Result<UserProfile>>()
        val repository = object : Repository() {
            override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest) =
                withContext(NonCancellable) { delayed.await() }
        }
        val main = own(ProfileViewModel(repository, browsing))
        val separate = own(ProfileViewModel(repository, browsing, accountId = testAccount.accountId))
        advanceUntilIdle(); main.loadProfile(); advanceUntilIdle()
        separate.updateProfile(ProfileEditRequest("Old edit", "old biography", false, false)); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        delayed.complete(Result.success(testProfile().copy(noteHtml = "old biography")))
        advanceUntilIdle()
        assertEquals(secondAccount.instanceUrl, main.uiState.value.profile!!.noteHtml)
        assertNull(separate.uiState.value.profile)
        assertNull(main.uiState.value.message)
        assertNull(separate.uiState.value.message)
    }

    @Test fun anOlderRefreshCannotOverwriteAnEditFromTheOtherEntry() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val oldHeader = CompletableDeferred<Result<UserProfile>>()
        var delayRefresh = false
        val repository = object : Repository() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                if (delayRefresh) oldHeader.await() else super.getProfileHeader(session, accountId)
            override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest) =
                Result.success(testProfile().copy(noteHtml = request.note))
        }
        val main = own(ProfileViewModel(repository, browsing))
        val separate = own(ProfileViewModel(repository, browsing, accountId = testAccount.accountId))
        advanceUntilIdle(); main.loadProfile(); advanceUntilIdle()
        delayRefresh = true
        main.refreshProfile(); runCurrent()
        separate.updateProfile(ProfileEditRequest("Me", "latest biography", false, false)); runCurrent()
        oldHeader.complete(Result.success(testProfile().copy(noteHtml = "old biography")))
        advanceUntilIdle()
        assertEquals("latest biography", main.uiState.value.profile!!.noteHtml)
        assertEquals("latest biography", separate.uiState.value.profile!!.noteHtml)
        assertFalse(main.uiState.value.isRefreshingProfile)
    }

    @Test fun storedOwnAccountChangesDoNotRenameAnotherUsersProfile() = runTest(dispatcher) {
        val sessions = MutableStateFlow(listOf(testAccount))
        val auth = object : AuthRepository {
            override fun observeSessions() = sessions
            override suspend fun restoreSession() = testAccount
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun logout() = Unit
        }
        val model = own(ProfileViewModel(Repository(), BrowsingSession().apply { activate(testAccount) },
            auth, accountId = "another-user"))
        advanceUntilIdle()
        val original = model.uiState.value.profile
        sessions.value = listOf(testAccount.copy(displayName = "Renamed self", avatarRevision = 7))
        advanceUntilIdle()
        assertEquals(original, model.uiState.value.profile)
        model.beginProfileEdit()
        assertFalse(model.profileImageEditState.value.isOpen)
    }

    @Test fun votesUpdateCachedTabsAndBothProfileEntries() = runTest(dispatcher) {
        val poll = StatusPoll("opaque-poll", null, false, false, 0, 0, false, emptySet(),
            listOf(PollOption("First", 0), PollOption("Second", 0)))
        val post = testStatus().copy(poll = poll)
        val requestedChoices = mutableListOf<Set<Int>>()
        val repository = object : Repository() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, maxId: String?) = Result.success(TimelinePage(listOf(post), null, true))
            override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String,
                choices: Set<Int>): Result<TimelineStatus> {
                assertEquals("opaque-poll", pollId)
                requestedChoices += choices
                return Result.success(post.copy(poll = poll.copy(voted = true, ownVotes = choices, votesCount = 1)))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val main = own(ProfileViewModel(repository, browsing))
        val separate = own(ProfileViewModel(repository, browsing, accountId = testAccount.accountId))
        advanceUntilIdle(); main.loadProfile(); advanceUntilIdle()
        separate.prepareProfileTab(ProfileStatusTab.Replies); advanceUntilIdle()
        separate.votePoll(post, setOf(1)); advanceUntilIdle()
        assertEquals(listOf(setOf(1)), requestedChoices)
        for (model in listOf(main, separate)) {
            assertTrue(model.uiState.value.profile!!.statuses.single().poll!!.voted == true)
            assertTrue(model.uiState.value.profileTabs.values.all { it.statuses.single().poll!!.ownVotes == setOf(1) })
        }
    }
}
