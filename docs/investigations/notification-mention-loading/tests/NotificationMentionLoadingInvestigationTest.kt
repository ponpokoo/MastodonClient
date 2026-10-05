package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.CachedNotifications
import io.github.ponpokoo.mastodonclient.domain.model.NotificationPage
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import io.github.ponpokoo.mastodonclient.feature.timeline.NotificationFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Characterizes the investigated implementation, not a specification for future fixes. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationMentionLoadingInvestigationTest : ScreenViewModelTestBase() {
    @Test fun nonReactionHistoryKeepsPagingUntilTheEntireHistoryReturnsEmpty() = runTest(dispatcher) {
        val requestedCursors = mutableListOf<String?>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                val pageIndex = requestedCursors.size
                requestedCursors += maxId
                return if (pageIndex == 25) Result.success(NotificationPage(emptyList(), null, true))
                else Result.success(NotificationPage(
                    (1..80).map { testNotification("favourite-$pageIndex-$it").copy(type = "favourite") },
                    "page-$pageIndex", false,
                ))
            }
        }
        val viewModel = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        repeat(24) {
            assertTrue(viewModel.uiState.value.notifications.all { it.status?.supportsEmojiReactions == false })
            assertTrue(viewModel.uiState.value.notifications.filter(NotificationFilter.Reactions::includes).isEmpty())
            assertFalse(viewModel.uiState.value.notificationsEndReached)
            viewModel.loadNextNotifications()
            advanceUntilIdle()
        }
        assertEquals(2_000, viewModel.uiState.value.notifications.size)
        assertEquals(25, requestedCursors.size)
        assertFalse(viewModel.uiState.value.notificationsEndReached)
        viewModel.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(26, requestedCursors.size)
        assertTrue(viewModel.uiState.value.notificationsEndReached)
        assertNull(viewModel.uiState.value.notificationsNextMaxId)
        viewModel.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(26, requestedCursors.size)
        assertEquals(listOf(null) + (0..24).map { "page-$it" }, requestedCursors)
    }

    @Test fun completedPageWaitsForPendingMarkerBeforeBeingDisplayed() = runTest(dispatcher) {
        val marker = CompletableDeferred<Result<String?>>()
        var pageCompleted = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = marker.await()
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                pageCompleted = true
                return Result.success(NotificationPage(listOf(testNotification()), null, true))
            }
        }
        val viewModel = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertTrue(pageCompleted)
        assertTrue(viewModel.uiState.value.notifications.isEmpty())
        assertTrue(viewModel.uiState.value.isLoadingNotifications)
        marker.complete(Result.failure(IllegalStateException("marker unavailable")))
        advanceUntilIdle()
        assertEquals(listOf("notification"), viewModel.uiState.value.notifications.map { it.id })
        assertFalse(viewModel.uiState.value.isLoadingNotifications)
    }

    @Test fun cachedMentionDisappearsWhenFreshMixedPageDoesNotContainItAndReturnsAfterPaging() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<NotificationPage>>()
        val olderMention = testNotification("older-mention")
        val requestedCursors = mutableListOf<String?>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications(listOf(olderMention)))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requestedCursors += maxId
                return if (maxId == null) response.await()
                else Result.success(NotificationPage(listOf(olderMention), "older-mention", false))
            }
        }
        val viewModel = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(listOf(olderMention), viewModel.uiState.value.notifications.filter(NotificationFilter.Mentions::includes))
        response.complete(Result.success(NotificationPage(
            (1..80).map { testNotification("favourite-$it").copy(type = "favourite") }, "favourite-80", false,
        )))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.notifications.filter(NotificationFilter.Mentions::includes).isEmpty())
        assertFalse(viewModel.uiState.value.notificationsEndReached)
        viewModel.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(listOf(olderMention), viewModel.uiState.value.notifications.filter(NotificationFilter.Mentions::includes))
        assertEquals(listOf(null, "favourite-80"), requestedCursors)
    }

    @Test fun returningToNotificationsDoesNotCatchUpWithoutStreamButForegroundReturnDoes() = runTest(dispatcher) {
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requests++
                return Result.success(NotificationPage(listOf(testNotification(if (requests == 1) "old" else "new")), null, true))
            }
        }
        val viewModel = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        viewModel.onNotificationsHidden()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(1, requests)
        assertEquals(listOf("old"), viewModel.uiState.value.notifications.map { it.id })
        viewModel.onAppForeground()
        advanceUntilIdle()
        assertEquals(2, requests)
        assertTrue(viewModel.uiState.value.notifications.any { it.id == "new" })
    }

    @Test fun malformedUnrelatedNotificationFailsTheWholeMixedPage() = runTest {
        MockWebServer().use { server ->
            val mention = """{"id":"mention","type":"mention","created_at":"2026-10-06T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"}}"""
            val malformedFavourite = """{"id":"other","type":"favourite","created_at":"2026-10-06T00:00:01Z","account":null}"""
            server.enqueue(MockResponse().setBody("[$malformedFavourite,$mention]"))
            server.enqueue(MockResponse().setBody("[$mention]"))
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val mixed = repository.getNotifications(session)
            assertTrue(mixed.isFailure)
            assertNotNull(mixed.exceptionOrNull())
            assertEquals("mention", repository.getNotifications(session).getOrThrow().notifications.single().id)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/api/v1/notifications?limit=80", request.path)
                assertNull(request.requestUrl!!.queryParameter("types[]"))
            }
        }
    }
}
