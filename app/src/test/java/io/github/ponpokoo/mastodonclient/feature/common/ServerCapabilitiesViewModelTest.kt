package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerCapabilitiesViewModelTest : ScreenViewModelTestBase() {
    @Test fun liveCacheUpdatesReachStatusActionsProfileHistoryAndNotifications() = runTest(dispatcher) {
        val capabilities = MutableStateFlow(ServerCapabilities(reactionApi = EmojiReactionApi.Fedibird, supportsEmojiReactions = true))
        val repository = object : ScreenRepositoryFake() {
            override fun observeServerCapabilities(session: AccountSession) = capabilities
            override suspend fun getServerCapabilities(session: AccountSession) = Result.success(capabilities.value)
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val common = own(ServerCapabilitiesViewModel(repository, browsing))
        val profile = own(ProfileViewModel(repository, browsing))
        val notifications = own(NotificationsViewModel(repository, browsing))
        runCurrent(); profile.loadProfile(); advanceUntilIdle()
        assertTrue(common.state.value.capabilities.supportsReactionActions)
        assertTrue(profile.uiState.value.supportsReactionHistory)
        assertTrue(notifications.uiState.value.canExplore(NotificationCategory.Reactions))

        capabilities.value = ServerCapabilities(reactionApi = EmojiReactionApi.Pleroma, supportsEmojiReactions = true)
        runCurrent()
        assertFalse(common.state.value.capabilities.supportsReactionActions)
        assertFalse(profile.uiState.value.supportsReactionHistory)
        assertTrue(notifications.uiState.value.canExplore(NotificationCategory.Reactions))

        capabilities.value = ServerCapabilities(reactionApi = EmojiReactionApi.Unsupported)
        runCurrent()
        assertFalse(notifications.uiState.value.canExplore(NotificationCategory.Reactions))
        assertEquals(capabilities.value, common.state.value.capabilities)
    }

    @Test fun pendingCheckIsSharedAndOldRoundTripResponseCannotEnableAnotherSession() = runTest(dispatcher) {
        val old = CompletableDeferred<Result<ServerCapabilities>>()
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<ServerCapabilities> =
                if (++requests == 1) withContext(NonCancellable) { old.await() }
                else Result.success(ServerCapabilities(reactionApi = EmojiReactionApi.Unsupported))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(ServerCapabilitiesViewModel(repository, browsing))
        runCurrent(); model.refresh(); model.refresh(); runCurrent()
        assertEquals(1, requests)
        browsing.activate(secondAccount); runCurrent()
        browsing.activate(testAccount); runCurrent()
        old.complete(Result.success(ServerCapabilities(reactionApi = EmojiReactionApi.Fedibird)))
        advanceUntilIdle()
        assertEquals(3, requests)
        assertEquals(browsing.snapshot.value, model.state.value.snapshot)
        assertFalse(model.state.value.capabilities.supportsReactionActions)
        browsing.activate(null); runCurrent()
        assertEquals(EmojiReactionApi.Unknown, model.state.value.capabilities.reactionApi)
    }
}
