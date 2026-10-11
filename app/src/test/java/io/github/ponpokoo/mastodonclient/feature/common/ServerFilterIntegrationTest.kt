package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.notifications.*
import io.github.ponpokoo.mastodonclient.feature.timeline.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerFilterIntegrationTest : ScreenViewModelTestBase() {
    @Test fun matchingTextAloneDoesNotHidePostsOrNotifications() {
        val filter = ServerFilter("filter", "server rule", setOf("home", "notifications"), action = "hide",
            keywords = listOf(FilterKeyword(keyword = "spoiler")))
        val moderation = AccountModerationState(serverFilters = mapOf(testAccount.sessionId to listOf(filter)))
        val status = testStatus().copy(contentHtml = "SPOILER", spoilerText = "spoiler")
        assertEquals(1, TimelineUiState(statuses = listOf(status)).withModeration(moderation, testAccount).statuses.size)
        val notification = testNotification().copy(status = status)
        assertEquals(1, NotificationsUiState(notifications = listOf(notification)).withModeration(moderation, testAccount).notifications.size)
        val matched = status.copy(filtered = listOf(StatusFilterResult(filter)))
        assertTrue(TimelineUiState(statuses = listOf(matched)).withModeration(moderation, testAccount).statuses.isEmpty())
        assertTrue(NotificationsUiState(notifications = listOf(notification.copy(status = matched))).withModeration(moderation, testAccount).notifications.isEmpty())
    }

    @Test fun hideFiltersAllNotificationTabsAndCountsWithoutChangingCursorsOrNotificationsWithoutPosts() {
        val filter = ServerFilter("filter", "hidden", setOf("notifications"), action = "hide")
        val hidden = testNotification("hidden").copy(status = testStatus().copy(filtered = listOf(StatusFilterResult(filter))))
        val reaction = hidden.copy(id = "reaction", type = "emoji_reaction")
        val kept = testNotification("kept")
        val follow = testNotification("follow").copy(type = "follow", status = null)
        val ids = setOf("hidden", "reaction", "kept", "follow")
        val state = NotificationsUiState(notifications = listOf(hidden, reaction, kept, follow), notificationsNextMaxId = "all-cursor",
            pendingNewNotificationIds = ids, highlightedNotificationIds = ids,
            refreshResult = NotificationsRefreshResult(1, true), shownNewNotice = ShownNewNotice(2, ids.size),
            lists = mapOf(NotificationCategory.Mentions to NotificationListState(listOf(hidden, kept), nextMaxId = "mention-cursor"),
                NotificationCategory.Reactions to NotificationListState(listOf(reaction), nextMaxId = "reaction-cursor")))
        val visible = state.withModeration(AccountModerationState(), testAccount)
        assertEquals(listOf("kept", "follow"), visible.notifications.map { it.id })
        assertEquals(listOf("kept"), visible.list(NotificationCategory.Mentions).notifications.map { it.id })
        assertTrue(visible.list(NotificationCategory.Reactions).notifications.isEmpty())
        assertEquals("all-cursor", visible.notificationsNextMaxId)
        assertEquals("mention-cursor", visible.list(NotificationCategory.Mentions).nextMaxId)
        assertEquals("reaction-cursor", visible.list(NotificationCategory.Reactions).nextMaxId)
        assertEquals(setOf("kept", "follow"), visible.pendingNewNotificationIds)
        assertEquals(setOf("kept", "follow"), visible.highlightedNotificationIds)
        assertEquals(2, visible.shownNewNotice?.count)
        val onlyHidden = state.copy(notifications = listOf(hidden), lists = emptyMap(), pendingNewNotificationIds = setOf("hidden"))
            .withModeration(AccountModerationState(), testAccount)
        assertFalse(onlyHidden.refreshResult!!.hasNewNotifications)
        assertNull(onlyHidden.shownNewNotice)
    }

    @Test fun hideDoesNotDeleteSourceAndRemovalRestoresCachedNotificationsAndCounts() = runTest(dispatcher) {
        val filter = ServerFilter("filter", "title", setOf("notifications"), action = "hide")
        val status = testStatus().copy(filtered = listOf(StatusFilterResult(filter)))
        val source = MutableStateFlow(NotificationsUiState(notifications = listOf(testNotification().copy(status = status)),
            pendingNewNotificationIds = setOf("notification"), highlightedNotificationIds = setOf("notification")))
        val repository = object : ScreenRepositoryFake() {
            override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to ServerAccountSettings(testAccount.instanceUrl, testAccount.accountId, filters = listOf(filter))))
        }
        val visible = source.moderated(backgroundScope, repository, { testAccount }) { value, moderation, account -> value.withModeration(moderation, account) }
        runCurrent()
        assertTrue(visible.value.notifications.isEmpty()); assertTrue(visible.value.pendingNewNotificationIds.isEmpty())
        assertEquals(1, source.value.notifications.size)
        repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(filters = emptyList()) }
        runCurrent(); assertEquals(1, visible.value.notifications.size); assertEquals(setOf("notification"), visible.value.pendingNewNotificationIds)
    }
    @Test fun warningPreservesNotificationAndAccountBlockStillTakesPrecedence() {
        val filter = ServerFilter("filter", "reason", setOf("notifications"))
        val notification = testNotification().copy(status = testStatus().copy(contentHtml = "local word", filtered = listOf(StatusFilterResult(filter))))
        val state = NotificationsUiState(notifications = listOf(notification))
        val warned = state.withModeration(AccountModerationState(), testAccount)
        assertEquals("reason", warned.notifications.single().status!!.filterDisplay.warnings.single().title)
        assertTrue(state.withModeration(AccountModerationState().changed(testAccount, notification.status!!.author.id, AccountRelationship(blocking = true)), testAccount).notifications.isEmpty())
        assertEquals(1, state.copy(notifications = listOf(notification.copy(status = null))).withModeration(AccountModerationState(), testAccount).notifications.size)
    }
    @Test fun homeAndPublicUseTheirOwnContextsAndBoostsKeepServerMatches() {
        val filter = ServerFilter("filter", "home only", setOf("home"), action = "hide")
        val post = testStatus().copy(filtered = listOf(StatusFilterResult(filter)), boostedBy = testStatus().author)
        assertTrue(TimelineUiState(statuses = listOf(post)).withModeration(AccountModerationState(), testAccount).statuses.isEmpty())
        assertEquals(1, TimelineUiState(selectedFeed = TimelineFeed.Local, statuses = listOf(post)).withModeration(AccountModerationState(), testAccount).statuses.size)
    }
}
