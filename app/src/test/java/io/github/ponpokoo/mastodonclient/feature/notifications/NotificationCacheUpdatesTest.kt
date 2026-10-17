package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCacheUpdatesTest : ScreenViewModelTestBase() {
    private class RecordingRepository : ScreenRepositoryFake() {
        val mention = testNotification("mention")
        val reaction = testNotification("reaction").copy(type = "emoji_reaction", status = testStatus("reaction-post"))
        val writes = mutableListOf<Pair<NotificationCategory, List<TimelineNotification>>>()

        override suspend fun getNotificationCapabilities(session: AccountSession) =
            Result.success(NotificationCapabilities(true, true))

        override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(NotificationPage(listOf(mention, reaction), null, true))

        override suspend fun cacheNotificationCategory(
            session: AccountSession,
            category: NotificationCategory,
            notifications: List<TimelineNotification>,
        ): Result<Unit> {
            writes += category to notifications
            return Result.success(Unit)
        }
    }

    private fun TestScope.loadCategories(viewModel: NotificationsViewModel) {
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        viewModel.selectCategory(NotificationCategory.Mentions)
        advanceUntilIdle()
        viewModel.selectCategory(NotificationCategory.Reactions)
        advanceUntilIdle()
    }

    @Test fun ordinaryTimelineEventsDoNotRewriteRestoredNotificationLists() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        loadCategories(viewModel)
        val before = viewModel.uiState.value
        repository.writes.clear()

        repeat(100) {
            browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(
                TimelineStreamEvent.StatusAdded(testStatus("new-$it"))))
        }
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.Connected))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(
            TimelineStreamEvent.StatusAdded(testStatus().copy(contentHtml = "edited"), isEdit = true)))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.TagUpdated(
            SearchTag("test", "https://one.example/tags/test", null, true)))
        advanceUntilIdle()

        assertTrue(repository.writes.isEmpty())
        assertEquals(before, viewModel.uiState.value)
    }

    @Test fun receivedNotificationOnlyPersistsChangedCategoriesAndIdenticalReplayDoesNotWrite() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        loadCategories(viewModel)
        repository.writes.clear()
        val live = testNotification("live")

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(live)))
        advanceUntilIdle()

        assertEquals(listOf(NotificationCategory.All, NotificationCategory.Mentions), repository.writes.map { it.first })
        assertTrue(repository.writes.all { it.second.first() == live })
        assertEquals(setOf("live"), viewModel.uiState.value.pendingNewNotificationIds)
        assertEquals(listOf("reaction"), viewModel.uiState.value.list(NotificationCategory.Reactions).notifications.map { it.id })
        repository.writes.clear()

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(live.copy())))
        advanceUntilIdle()

        assertTrue(repository.writes.isEmpty())
        assertEquals(setOf("live"), viewModel.uiState.value.pendingNewNotificationIds)

        val changed = live.copy(status = live.status!!.copy(contentHtml = "updated notification"))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(changed)))
        advanceUntilIdle()

        assertEquals(listOf(NotificationCategory.All, NotificationCategory.Mentions), repository.writes.map { it.first })
        assertTrue(repository.writes.all { it.second.first() == changed })
        assertEquals(setOf("live"), viewModel.uiState.value.pendingNewNotificationIds)
    }

    @Test fun statusActionsOnlyPersistCategoriesWithChangedNotificationStatuses() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        loadCategories(viewModel)
        repository.writes.clear()
        val updated = testStatus().copy(favourited = true, favouritesCount = 1)

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(updated))
        advanceUntilIdle()

        assertEquals(listOf(NotificationCategory.All, NotificationCategory.Mentions), repository.writes.map { it.first })
        assertTrue(repository.writes.all { (_, notifications) -> notifications.first().status == updated })
        assertEquals(true, viewModel.uiState.value.list(NotificationCategory.Mentions).notifications.single().status?.favourited)
        repository.writes.clear()

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(updated.copy()))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(testStatus("unrelated")))
        advanceUntilIdle()

        assertTrue(repository.writes.isEmpty())
    }

    @Test fun localAndStreamDeletionOnlyPersistCategoriesThatReferenceTheDeletedPost() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        loadCategories(viewModel)
        repository.writes.clear()

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("post"))
        advanceUntilIdle()

        assertEquals(listOf(NotificationCategory.All, NotificationCategory.Mentions), repository.writes.map { it.first })
        assertTrue(repository.writes.all { (_, notifications) -> notifications.first().status == null })
        assertNull(viewModel.uiState.value.list(NotificationCategory.Mentions).notifications.single().status)
        repository.writes.clear()

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusDeleted("reaction-post")))
        advanceUntilIdle()

        assertEquals(listOf(NotificationCategory.All, NotificationCategory.Reactions), repository.writes.map { it.first })
        assertTrue(repository.writes.all { (_, notifications) -> notifications.all { it.status == null } })
        assertNull(viewModel.uiState.value.list(NotificationCategory.Reactions).notifications.single().status)
        repository.writes.clear()

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("unrelated"))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusDeleted("post")))
        advanceUntilIdle()

        assertTrue(repository.writes.isEmpty())
    }
}
