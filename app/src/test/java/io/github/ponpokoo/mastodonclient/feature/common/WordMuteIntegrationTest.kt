package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WordMuteIntegrationTest : ScreenViewModelTestBase() {
    @Test fun filtersBodyCwAndBoostsRestoresWithoutFetchingAndDoesNotFilterNotificationsOrOtherAccount() = runTest(dispatcher) {
        val body = testStatus("body").copy(contentHtml = "Hello SPOILER")
        val cw = testStatus("cw").copy(spoilerText = "Spoiler warning")
        val boost = body.copy(timelineId = "boost", boostedBy = testStatus().author)
        val kept = testStatus("kept")
        var fetches = 0
        val repository = object : ScreenRepositoryFake() {
            override val wordMutes = MutableStateFlow<Map<String, List<String>>>(emptyMap())
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                fetches++; return Result.success(TimelinePage(listOf(body, cw, boost, kept), "server-cursor", false))
            }
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification().copy(status = body)), null, true))
        }
        val browsing = BrowsingSession().also { it.activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing)); val notifications = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle(); notifications.loadNotifications(); advanceUntilIdle()
        val initialFetches = fetches
        repository.wordMutes.value = mapOf(testAccount.sessionId to listOf("spoiler")); advanceUntilIdle()
        assertEquals(listOf("kept"), timeline.uiState.value.statuses.map { it.timelineId })
        assertEquals("server-cursor", timeline.uiState.value.nextMaxId)
        assertEquals(1, notifications.uiState.value.notifications.size)
        repository.wordMutes.value = emptyMap(); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size); assertEquals(initialFetches, fetches)
        repository.wordMutes.value = mapOf(testAccount.sessionId to listOf("spoiler"))
        browsing.activate(secondAccount); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
    }
}
