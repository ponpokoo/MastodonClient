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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileImageRefreshViewModelTest : ScreenViewModelTestBase() {
    private class Repository : ScreenRepositoryFake() {
        var header = Result.success(testProfile().copy(headerUrl = "https://images.example/header",
            author = testProfile().author.copy(avatarUrl = "https://images.example/avatar")))
        override suspend fun getProfileHeader(session: AccountSession, accountId: String) = header
        override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest) = header
    }
    private class Auth : AuthRepository {
        val sessions = MutableStateFlow(listOf(testAccount))
        override fun observeSessions() = sessions
        override suspend fun restoreSession() = testAccount
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun logout() = Unit
    }
    private val edit = ProfileEditRequest("Me", "note", false, false)

    @Test fun ownProfileRefreshesSameUrlsWithoutPersistedRevisionAndKeepsGenerationOnApiFailure() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(OwnProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        val initial = vm.uiState.value
        assertEquals(0L, initial.profile!!.author.avatarRevision)
        assertNotEquals(0L, initial.imageRefreshRevision)
        vm.refreshProfile()
        advanceUntilIdle()
        val refreshed = vm.uiState.value
        assertNotEquals(initial.imageRefreshRevision, refreshed.imageRefreshRevision)
        assertEquals(initial.profile!!.headerUrl, refreshed.profile!!.headerUrl)
        repository.header = Result.failure(IllegalStateException("offline"))
        vm.refreshProfile()
        advanceUntilIdle()
        assertEquals(refreshed.imageRefreshRevision, vm.uiState.value.imageRefreshRevision)
        assertEquals(refreshed.profile, vm.uiState.value.profile)
        repository.header = Result.success(refreshed.profile!!.copy(headerUrl = "https://images.example/new-header"))
        vm.updateProfile(edit)
        advanceUntilIdle()
        assertEquals("https://images.example/new-header", vm.uiState.value.profile!!.headerUrl)
        assertNotEquals(refreshed.imageRefreshRevision, vm.uiState.value.imageRefreshRevision)
    }

    @Test fun accountProfileRefreshAndEditReloadImagesAndApplyEditedHeader() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(AccountProfileViewModel("me", repository, Auth()))
        advanceUntilIdle()
        val initial = vm.uiState.value
        vm.refresh()
        advanceUntilIdle()
        val refreshed = vm.uiState.value
        assertNotEquals(initial.imageRefreshRevision, refreshed.imageRefreshRevision)
        repository.header = Result.failure(IllegalStateException("offline"))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(refreshed.imageRefreshRevision, vm.uiState.value.imageRefreshRevision)
        assertEquals(refreshed.profile, vm.uiState.value.profile)
        repository.header = Result.success(refreshed.profile!!.copy(headerUrl = "https://images.example/edited"))
        vm.updateProfile(edit)
        advanceUntilIdle()
        assertEquals("https://images.example/edited", vm.uiState.value.profile!!.headerUrl)
        assertNotEquals(refreshed.imageRefreshRevision, vm.uiState.value.imageRefreshRevision)
    }

    @Test fun sessionRoundTripResetsImageOwnerAndRejectsLateOldHeader() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<UserProfile>>()
        var delayOld = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                if (delayOld && session.sessionId == testAccount.sessionId) withContext(NonCancellable) { delayed.await() }
                else Result.success(testProfile().copy(noteHtml = session.sessionId))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        val firstOwner = vm.uiState.value.imageSessionKey
        delayOld = true
        vm.refreshProfile()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        val current = vm.uiState.value
        delayed.complete(Result.success(testProfile().copy(noteHtml = "obsolete")))
        advanceUntilIdle()
        assertEquals(current, vm.uiState.value)
        delayOld = false
        browsing.activate(testAccount)
        advanceUntilIdle()
        assertNotEquals(firstOwner, vm.uiState.value.imageSessionKey)
        assertEquals("one", vm.uiState.value.profile!!.noteHtml)
    }

    @Test fun registeredAvatarUpdatesTriggerImageReloadWithoutReloadingForNameOnlyChanges() = runTest(dispatcher) {
        val auth = Auth()
        val vm = own(OwnProfileViewModel(Repository(), BrowsingSession().apply { activate(testAccount) }, auth))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        val initial = vm.uiState.value.imageRefreshRevision
        auth.sessions.value = listOf(testAccount.copy(displayName = "Renamed"))
        advanceUntilIdle()
        assertEquals(initial, vm.uiState.value.imageRefreshRevision)
        auth.sessions.value = listOf(testAccount.copy(avatarRevision = 1))
        advanceUntilIdle()
        assertNotEquals(initial, vm.uiState.value.imageRefreshRevision)
    }
}
