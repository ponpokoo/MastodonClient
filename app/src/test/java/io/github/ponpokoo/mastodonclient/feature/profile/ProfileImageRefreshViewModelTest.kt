package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.AccountAvatarRepository
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

    private class Avatars : AccountAvatarRepository {
        val urls = mutableListOf<String>()
        override suspend fun revalidate(url: String): Boolean { urls += url; return true }
    }

    @Test fun ownProfileRefreshesSameUrlsWithoutPersistedRevisionAndKeepsGenerationOnApiFailure() = runTest(dispatcher) {
        val repository = Repository()
        val avatars = Avatars()
        val vm = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }, avatarRepository = avatars))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        val initial = vm.uiState.value
        assertTrue(avatars.urls.isEmpty())
        assertNotEquals(0L, initial.headerRefreshRevision)
        vm.refreshProfile()
        advanceUntilIdle()
        val refreshed = vm.uiState.value
        assertNotEquals(initial.headerRefreshRevision, refreshed.headerRefreshRevision)
        assertEquals(listOf("https://images.example/avatar"), avatars.urls)
        assertEquals(initial.profile!!.headerUrl, refreshed.profile!!.headerUrl)
        repository.header = Result.failure(IllegalStateException("offline"))
        vm.refreshProfile()
        advanceUntilIdle()
        assertEquals(refreshed.headerRefreshRevision, vm.uiState.value.headerRefreshRevision)
        assertEquals(refreshed.profile, vm.uiState.value.profile)
        // A metadata failure does not prevent the explicit image request.
        assertEquals(2, avatars.urls.size)
        repository.header = Result.success(refreshed.profile!!.copy(headerUrl = "https://images.example/new-header"))
        vm.updateProfile(edit)
        advanceUntilIdle()
        assertEquals("https://images.example/new-header", vm.uiState.value.profile!!.headerUrl)
        assertNotEquals(refreshed.headerRefreshRevision, vm.uiState.value.headerRefreshRevision)
        assertEquals(2, avatars.urls.size) // Text-only edits do not revalidate the icon.
        vm.updateProfile(edit.copy(headerFilePath = "header.png"))
        advanceUntilIdle()
        assertEquals(2, avatars.urls.size)
        vm.updateProfile(edit.copy(avatarFilePath = "avatar.png"))
        advanceUntilIdle()
        assertEquals(3, avatars.urls.size)
    }

    @Test fun accountProfileRefreshAndEditReloadImagesAndApplyEditedHeader() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }, Auth(), accountId = "me"))
        advanceUntilIdle()
        val initial = vm.uiState.value
        vm.refreshProfile()
        advanceUntilIdle()
        val refreshed = vm.uiState.value
        assertNotEquals(initial.headerRefreshRevision, refreshed.headerRefreshRevision)
        repository.header = Result.failure(IllegalStateException("offline"))
        vm.refreshProfile()
        advanceUntilIdle()
        assertEquals(refreshed.headerRefreshRevision, vm.uiState.value.headerRefreshRevision)
        assertEquals(refreshed.profile, vm.uiState.value.profile)
        repository.header = Result.success(refreshed.profile!!.copy(headerUrl = "https://images.example/edited"))
        vm.updateProfile(edit)
        advanceUntilIdle()
        assertEquals("https://images.example/edited", vm.uiState.value.profile!!.headerUrl)
        assertNotEquals(refreshed.headerRefreshRevision, vm.uiState.value.headerRefreshRevision)
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
        val vm = own(ProfileViewModel(repository, browsing))
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

    @Test fun metadataSynchronizationChangesNeitherHeaderGenerationNorAvatarValidation() = runTest(dispatcher) {
        val auth = Auth()
        val avatars = Avatars()
        val vm = own(ProfileViewModel(Repository(), BrowsingSession().apply { activate(testAccount) }, auth, avatarRepository = avatars))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        val initial = vm.uiState.value.headerRefreshRevision
        auth.sessions.value = listOf(testAccount.copy(displayName = "Renamed"))
        advanceUntilIdle()
        assertEquals(initial, vm.uiState.value.headerRefreshRevision)
        auth.sessions.value = listOf(testAccount.copy(avatarUrl = "https://images.example/changed"))
        advanceUntilIdle()
        assertEquals(initial, vm.uiState.value.headerRefreshRevision)
        assertEquals("https://images.example/changed", vm.uiState.value.profile!!.author.avatarUrl)
        assertTrue(avatars.urls.isEmpty())
    }

    @Test fun editForcesOnceAcrossBothProfileEntriesAndImageFailureDoesNotBlockMetadata() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val repository = Repository()
        val urls = mutableListOf<String>()
        val avatars = AccountAvatarRepository { url -> urls += url; throw java.io.IOException("offline image") }
        val main = own(ProfileViewModel(repository, browsing, avatarRepository = avatars))
        val detail = own(ProfileViewModel(repository, browsing, accountId = "me", avatarRepository = avatars))
        advanceUntilIdle()
        main.loadProfile()
        advanceUntilIdle()
        main.updateProfile(edit.copy(avatarFilePath = "avatar.png"))
        advanceUntilIdle()
        assertEquals(listOf("https://images.example/avatar"), urls)
        assertEquals(main.uiState.value.profile!!.author, detail.uiState.value.profile!!.author)
        assertEquals("プロフィールを更新しました", main.uiState.value.message)
        assertFalse(main.profileImageEditState.value.isSaving)
    }

    @Test fun failedIconEditDoesNotForceAndOtherProfilesDoNotForceOnPull() = runTest(dispatcher) {
        val repository = Repository()
        val avatars = Avatars()
        val vm = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }, avatarRepository = avatars))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        repository.header = Result.failure(IllegalStateException("save failed"))
        vm.updateProfile(edit.copy(avatarFilePath = "avatar.png"))
        advanceUntilIdle()
        assertTrue(avatars.urls.isEmpty())
        repository.header = Result.success(testProfile().copy(isOwnProfile = false,
            author = testProfile().author.copy(id = "someone")))
        val other = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) },
            accountId = "someone", avatarRepository = avatars))
        advanceUntilIdle()
        other.refreshProfile()
        advanceUntilIdle()
        assertTrue(avatars.urls.isEmpty())
    }

    @Test fun headerResponseStartedBeforeEditCannotForceAnObsoleteAvatarUrl() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<UserProfile>>()
        var delayed = false
        val initial = testProfile().copy(author = testProfile().author.copy(avatarUrl = "https://images.example/initial"))
        val edited = initial.copy(author = initial.author.copy(avatarUrl = "https://images.example/edited"))
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                if (delayed) withContext(NonCancellable) { gate.await() } else Result.success(initial)
            override suspend fun updateProfile(session: AccountSession, request: ProfileEditRequest) = Result.success(edited)
        }
        val avatars = Avatars()
        val vm = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }, avatarRepository = avatars))
        advanceUntilIdle()
        vm.loadProfile()
        advanceUntilIdle()
        delayed = true
        vm.refreshProfile()
        advanceUntilIdle()
        vm.updateProfile(edit.copy(avatarFilePath = "avatar.png"))
        advanceUntilIdle()
        gate.complete(Result.success(initial.copy(author = initial.author.copy(avatarUrl = "https://images.example/obsolete"))))
        advanceUntilIdle()
        assertEquals(listOf(initial.author.avatarUrl, edited.author.avatarUrl), avatars.urls)
        assertEquals(edited.author.avatarUrl, vm.uiState.value.profile!!.author.avatarUrl)
    }
}
