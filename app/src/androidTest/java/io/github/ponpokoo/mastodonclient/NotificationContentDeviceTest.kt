package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.NotificationFilter
import io.github.ponpokoo.mastodonclient.feature.timeline.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
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
        val notification = TimelineNotification("notification-1", "favourite", status.createdAt, author, status)

        composeRule.setContent {
            MaterialTheme {
                NotificationsContent(
                    state = TimelineUiState(notifications = listOf(notification), notificationsEndReached = true),
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
                    onMediaClick = {},
                    preferences = AppPreferences(),
                    listState = rememberLazyListState(),
                    selectedFilter = NotificationFilter.All,
                    onSelectFilter = {},
                )
            }
        }

        composeRule.onNodeWithTag("notification_status_quote").performClick()
        composeRule.runOnIdle { assertEquals("status-1", openedStatusId.get()) }
    }
}
