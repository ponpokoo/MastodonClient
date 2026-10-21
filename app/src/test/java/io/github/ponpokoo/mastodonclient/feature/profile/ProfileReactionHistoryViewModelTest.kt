package io.github.ponpokoo.mastodonclient.feature.profile

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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileReactionHistoryViewModelTest : ScreenViewModelTestBase() {
    @Test fun onlyOwnFedibirdCompatibleProfilesOfferReactionHistory() = runTest(dispatcher) {
        for (ownProfile in listOf(true, false)) for (api in EmojiReactionApi.entries) {
            var checks = 0
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                    Result.success(testProfile().copy(isOwnProfile = ownProfile))
                override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                    checks++
                    return Result.success(NotificationCapabilities(reactionApi = api))
                }
            }
            val model = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            advanceUntilIdle(); model.loadProfile(); advanceUntilIdle()
            assertEquals(ownProfile && api == EmojiReactionApi.Fedibird, model.uiState.value.supportsReactionHistory)
            assertEquals(if (ownProfile) 1 else 0, checks)
        }
    }

    @Test fun failedCapabilityCheckCanRetryAndMenuReopeningSharesThePendingRequest() = runTest(dispatcher) {
        val retry = CompletableDeferred<Result<NotificationCapabilities>>()
        var checks = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> =
                if (++checks == 1) Result.failure(IOException("offline")) else retry.await()
        }
        val model = own(ProfileViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.loadProfile(); advanceUntilIdle()
        assertFalse(model.uiState.value.supportsReactionHistory)
        model.loadReactionHistorySupport(); runCurrent()
        model.loadReactionHistorySupport(); runCurrent()
        assertEquals(2, checks)
        retry.complete(Result.success(NotificationCapabilities(reactionApi = EmojiReactionApi.Fedibird)))
        advanceUntilIdle()
        assertTrue(model.uiState.value.supportsReactionHistory)
    }

    @Test fun accountRoundTripDiscardsOldSupportEvenWhenCancellationIsIgnored() = runTest(dispatcher) {
        val old = CompletableDeferred<Result<NotificationCapabilities>>()
        var checks = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> =
                if (++checks == 1) withContext(NonCancellable) { old.await() }
                else Result.success(NotificationCapabilities(reactionApi = EmojiReactionApi.Unsupported))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(ProfileViewModel(repository, browsing))
        runCurrent(); model.loadProfile(); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        assertFalse(model.uiState.value.supportsReactionHistory)
        browsing.activate(testAccount); runCurrent()
        old.complete(Result.success(NotificationCapabilities(reactionApi = EmojiReactionApi.Fedibird)))
        advanceUntilIdle()
        assertEquals(3, checks)
        assertFalse(model.uiState.value.supportsReactionHistory)
    }
}
