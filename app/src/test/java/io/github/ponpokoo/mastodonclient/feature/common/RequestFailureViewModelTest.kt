package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestFailureViewModelTest : ScreenViewModelTestBase() {
    private fun failure(kind: RequestFailure) = RequestException(kind, "HTTP", IllegalStateException("cause"))
    private class Auth : AuthRepository {
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun restoreSession() = testAccount
        override suspend fun logout() = Unit
    }

    @Test fun notificationAndStatusActionsKeepAuthenticationGuidance() = runTest(dispatcher) {
        for (kind in listOf(RequestFailure.Unauthorized, RequestFailure.Forbidden)) {
            val error = failure(kind)
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> = Result.failure(error)
                override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> = Result.failure(error)
                override suspend fun setFedibirdReaction(session: AccountSession, statusId: String, emoji: String?): Result<TimelineStatus> = Result.failure(error)
                override suspend fun getStatusDetail(session: AccountSession, statusId: String) = Result.success(StatusDetail(testStatus(), emptyList(), emptyList()))
            }
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val notifications = own(NotificationsViewModel(repository, browsing))
            val actions = own(StatusActionsViewModel(repository, browsing))
            val detail = own(StatusDetailViewModel("post", repository, Auth()))
            advanceUntilIdle()
            notifications.onNotificationsVisible(); actions.toggleBookmark(testStatus()); detail.setReaction("smile")
            advanceUntilIdle()
            assertTrue(notifications.uiState.value.notificationsError.orEmpty().contains("再ログイン"))
            assertTrue(actions.uiState.value.actionMessage.orEmpty().contains("再ログイン"))
            assertTrue(detail.uiState.value.errorMessage.orEmpty().contains("再ログイン"))
            assertFalse(actions.uiState.value.actionMessage.orEmpty().contains("cause"))
        }
    }

    @Test fun timelineKeepsExpiredLoginAndRateLimitGuidanceDistinct() = runTest(dispatcher) {
        for ((kind, expected) in listOf(RequestFailure.Unauthorized to "ログインの有効期限", RequestFailure.RateLimited to "しばらく待って")) {
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> = Result.failure(failure(kind))
            }
            val vm = own(TimelineViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains(expected))
            assertFalse(vm.uiState.value.isInitialLoading)
        }
    }
}
