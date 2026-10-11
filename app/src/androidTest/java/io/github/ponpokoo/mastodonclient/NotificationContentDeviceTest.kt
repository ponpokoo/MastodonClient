package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.mutableStateOf
import io.github.ponpokoo.mastodonclient.domain.model.FilterPresentation
import io.github.ponpokoo.mastodonclient.domain.model.ServerFilter
import io.github.ponpokoo.mastodonclient.domain.model.ReadingPreferences
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationFilter
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationContentDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingAnywhereOnQuotedStatusOpensItsDetail() {
        val openedStatusId = AtomicReference<String?>(null)
        val author = StatusAuthor("account-1", "テストユーザー", "test@example.social", "")
        val status = TimelineStatus(
            timelineId = "status-1",
            statusId = "status-1",
            createdAt = "2026-09-09T00:00:00Z",
            author = author,
            boostedBy = null,
            contentHtml = "<p>通知に表示する投稿です</p>",
            spoilerText = "",
            sensitive = false,
            visibility = "public",
            url = null,
            repliesCount = 0,
            boostsCount = 0,
            favouritesCount = 0,
            mediaAttachments = emptyList(),
        )
        val notifier = StatusAuthor("account-2", "通知した人", "other@example.social", "")
        val notification = TimelineNotification("notification-1", "favourite", status.createdAt, notifier, status)

        composeRule.setContent {
            MaterialTheme {
                NotificationsContent(
                    state = NotificationsUiState(notifications = listOf(notification), notificationsEndReached = true),
                    padding = androidx.compose.foundation.layout.PaddingValues(),
                    onRefresh = {},
                    onLoadMore = {},
                    onStatusClick = openedStatusId::set,
                    onOpenLink = {},
                    onReply = {},
                    onBoost = {},
                    onFavourite = {},
                    onBookmark = {},
                    onReact = { _, _ -> },
                    onAccountClick = {},
                    onMediaClick = { _, _ -> },
                    preferences = AppPreferences(),
                    listStates = listOf(
                        rememberLazyListState(),
                        rememberLazyListState(),
                        rememberLazyListState(),
                    ),
                    selectedFilter = NotificationFilter.All,
                    onSelectFilter = {},
                    newNoticeMessage = null,
                    onNewNoticeClick = null,
                )
            }
        }

        composeRule.onNodeWithTag("notification_status_author_avatar", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("notification_status_quote", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals("status-1", openedStatusId.get()) }
    }
    @Test fun compactFilterWarningHidesBodyAndAuthorCwUntilBothAreRevealed() = warningScenario("favourite")
    @Test fun replyFilterWarningHidesBodyAndAuthorCwUntilBothAreRevealed() = warningScenario("mention")
    @Test fun compactCwFollowsReadingPreferenceWhileFilterWarningStillRequiresReveal() = readingScenario("favourite")
    @Test fun replyCwFollowsReadingPreferenceWhileFilterWarningStillRequiresReveal() = readingScenario("mention")

    private fun readingScenario(type: String) {
        val author = StatusAuthor("author", "投稿者", "author@example.org", "")
        val status = TimelineStatus("post", "post", "2026-10-12T00:00:00Z", author, null,
            "<p>本文</p>", "注意文", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        val current = mutableStateOf(status)
        val reading = mutableStateOf(io.github.ponpokoo.mastodonclient.feature.common.ReadingSettings("a"))
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings provides reading.value) {
                    NotificationsContent(state = NotificationsUiState(notifications = listOf(TimelineNotification("notice", type, status.createdAt, author, current.value)), notificationsEndReached = true),
                        padding = androidx.compose.foundation.layout.PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = {},
                        onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> }, preferences = AppPreferences(),
                        listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState()), selectedFilter = NotificationFilter.All,
                        onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null)
                }
            }
        }
        composeRule.onNodeWithText("本文").assertDoesNotExist()
        composeRule.runOnIdle { reading.value = reading.value.copy(preferences = ReadingPreferences(expandSpoilers = true)) }
        composeRule.onNodeWithText("本文").assertExists()
        composeRule.onNodeWithText("内容を隠す").performClick()
        composeRule.onNodeWithText("本文").assertDoesNotExist()
        composeRule.runOnIdle { current.value = status.copy(favouritesCount = 1) }
        composeRule.onNodeWithText("本文").assertDoesNotExist()
        composeRule.runOnIdle {
            reading.value = reading.value.copy(accountKey = "b")
            current.value = status.copy(filterDisplay = FilterPresentation(warnings = listOf(ServerFilter("warn", "警告", setOf("notifications")))))
        }
        composeRule.onNodeWithText("本文").assertDoesNotExist()
        composeRule.onNodeWithText("注意文").assertDoesNotExist()
        composeRule.onNodeWithTag("filter_reveal").performClick()
        composeRule.onNodeWithText("本文").assertExists()
    }
    private fun warningScenario(type: String) {
        val opened = AtomicReference<String?>(null)
        val author = StatusAuthor("author", "投稿者", "author@example.social", "")
        val filter = ServerFilter("filter", "ネタバレ", setOf("notifications"))
        val status = TimelineStatus("post", "post", "2026-10-11T00:00:00Z", author, null,
            "<p>折りたたむ本文</p>", "投稿者の注意文", false, "public", null, 0, 0, 0, mediaAttachments = emptyList(),
            filterDisplay = FilterPresentation(warnings = listOf(filter)), filterScope = "account-a")
        val current = mutableStateOf(status)
        composeRule.setContent {
            MaterialTheme {
                NotificationsContent(state = NotificationsUiState(notifications = listOf(TimelineNotification("notice", type, status.createdAt, author, current.value)), notificationsEndReached = true),
                    padding = androidx.compose.foundation.layout.PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = opened::set,
                    onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> }, preferences = AppPreferences(),
                    listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState()), selectedFilter = NotificationFilter.All,
                    onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null)
            }
        }
        composeRule.onNodeWithText("折りたたむ本文").assertDoesNotExist()
        composeRule.onNodeWithText("投稿者の注意文").assertDoesNotExist()
        composeRule.onNodeWithTag("filter_reveal").performClick()
        composeRule.runOnIdle { assertEquals(null, opened.get()) }
        composeRule.onNodeWithText("投稿者の注意文").assertExists()
        composeRule.onNodeWithText("折りたたむ本文").assertDoesNotExist()
        composeRule.onNodeWithText("内容を表示").performClick()
        composeRule.onNodeWithText("折りたたむ本文").assertExists()
        composeRule.runOnIdle { assertEquals(null, opened.get()) }
        // The shared warning surface handles taps, even when the parent status is clickable.
        composeRule.onNodeWithText("フィルター：ネタバレ").performClick()
        composeRule.onNodeWithText("折りたたむ本文").assertDoesNotExist()
        composeRule.onNodeWithText("投稿者の注意文").assertDoesNotExist()
        composeRule.onNodeWithTag("filter_reveal").performClick()
        composeRule.onNodeWithText("折りたたむ本文").assertExists()
        composeRule.onNodeWithText("投稿者の注意文").performClick()
        composeRule.onNodeWithText("折りたたむ本文").assertDoesNotExist()
        composeRule.onNodeWithText("投稿者の注意文").assertExists()
        composeRule.runOnIdle { assertEquals(null, opened.get()) }
        composeRule.runOnIdle { current.value = status.copy(filterScope = "account-b") }
        composeRule.onNodeWithText("投稿者の注意文").assertDoesNotExist()
    }

}
