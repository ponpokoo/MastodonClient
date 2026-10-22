package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.notifications.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationGroupingDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val author = StatusAuthor("me", "投稿者", "me@example.test", "")
    private val post = TimelineStatus("post", "post", "2020-01-01T00:00:00Z", author, null,
        "<p>投稿の本文はそのまま</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private fun event(id: String, kind: String = "favourite", emoji: String? = null) = TimelineNotification(
        id, kind, Instant.now().minusSeconds(300).toString(), StatusAuthor(id, "通知者$id", "$id@example.test", ""), post, emoji,
    )

    @Test fun groupingPreservesMenuAndAllFiveAvatarLinksWhileOnlyQuoteOpensPost() {
        val grouped = mutableStateOf(false)
        val openedPost = AtomicReference<String?>()
        val openedAccount = AtomicReference<String?>()
        val openedMenu = AtomicReference<TimelineStatus?>()
        val events = (1..7).map { event("$it") }
        rule.setContent { MastodonClientTheme {
            NotificationsContent(
                state = NotificationsUiState(notifications = events, isInitialPageLoaded = true, notificationsEndReached = true),
                padding = PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = openedPost::set,
                onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> },
                onMoreClick = openedMenu::set, onAccountClick = openedAccount::set, onMediaClick = { _, _ -> },
                preferences = AppPreferences(groupNotifications = grouped.value), listStates = List(3) { rememberLazyListState() },
                selectedFilter = NotificationCategory.All, onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null,
            )
        } }
        rule.onAllNodesWithText("投稿の本文はそのまま").onFirst().assertExists()
        rule.runOnIdle { grouped.value = true }
        rule.onAllNodesWithTag("notification_status_quote").assertCountEquals(1)
        rule.onNodeWithText("投稿の本文はそのまま").assertExists()
        rule.onNodeWithTag("notification_status_author_avatar", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("notification_actor_avatar", useUnmergedTree = true).assertCountEquals(5)
        rule.onNodeWithTag("notification_group_time", useUnmergedTree = true).assertTextEquals("5分前")
        for (id in 1..5) {
            rule.onNodeWithContentDescription("通知者${id}のプロフィール").performClick()
            rule.runOnIdle { assertEquals(id.toString(), openedAccount.get()); assertNull(openedPost.get()) }
        }
        rule.onNodeWithTag("notification_group_summary", useUnmergedTree = true).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(10f, height / 2f))
        }
        rule.onNodeWithTag("notification_group_time", useUnmergedTree = true).performTouchInput { click() }
        rule.onNodeWithTag("notification_type_icon", useUnmergedTree = true).performTouchInput { click() }
        rule.onNodeWithTag("notification_grouped_row").performTouchInput { click(topRight) }
        rule.runOnIdle { assertNull(openedPost.get()); assertEquals("5", openedAccount.get()) }
        rule.onNodeWithText("投稿の本文はそのまま", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle { assertEquals("post", openedPost.get()); assertEquals("5", openedAccount.get()) }
        rule.onNodeWithContentDescription("投稿メニュー").performClick()
        rule.runOnIdle { assertEquals(post, openedMenu.get()) }
    }

    @Test fun toggleAffectsOnlyAllWhileReactionTabKeepsSeparateNotifications() {
        val reactions = listOf(event("r1", "emoji_reaction", "☕"), event("r2", "emoji_reaction", "☕"), event("r3", "emoji_reaction", "👍"))
        rule.setContent { MastodonClientTheme {
            var grouped by remember { mutableStateOf(false) }
            var category by remember { mutableStateOf(NotificationCategory.All) }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NotificationDisplaySettings(grouped) { grouped = it }
                }
                Box(Modifier.weight(1f)) {
                    NotificationsContent(
                        state = NotificationsUiState(notifications = reactions, isInitialPageLoaded = true, notificationsEndReached = true,
                            capabilities = NotificationCapabilities(supportsEmojiReactions = true),
                            lists = mapOf(NotificationCategory.Reactions to NotificationListState(notifications = reactions, isLoaded = true, endReached = true))),
                        padding = PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = {},
                        onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> },
                        onAccountClick = {}, onMediaClick = { _, _ -> }, preferences = AppPreferences(groupNotifications = grouped),
                        listStates = List(3) { rememberLazyListState() }, selectedFilter = category, onSelectFilter = { category = it },
                        newNoticeMessage = null, onNewNoticeClick = null,
                    )
                }
            }
        } }
        rule.onAllNodesWithTag("notification_grouped_row").assertCountEquals(0)
        rule.onNodeWithTag("notification_display_settings").performClick()
        rule.onNodeWithTag("notification_group_toggle").assertIsOff().performClick().assertIsOn()
        androidx.test.espresso.Espresso.pressBack()
        rule.onAllNodesWithTag("notification_grouped_row").assertCountEquals(2)
        rule.onNodeWithTag("notification_filter_reactions").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("notification_grouped_row").assertCountEquals(0)
        rule.onAllNodesWithTag("notification_status_quote").assertCountEquals(3)
    }

    @Test fun groupedHeaderDoesNotBypassFilterWarning() {
        val opened = AtomicReference<String?>()
        val hiddenPost = post.copy(filterDisplay = FilterPresentation(warnings = listOf(ServerFilter("filter", "警告", setOf("notifications")))))
        rule.setContent { MastodonClientTheme {
            NotificationsContent(
                state = NotificationsUiState(notifications = listOf(event("1").copy(status = hiddenPost)), isInitialPageLoaded = true, notificationsEndReached = true),
                padding = PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = opened::set,
                onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> },
                onAccountClick = {}, onMediaClick = { _, _ -> }, preferences = AppPreferences(groupNotifications = true),
                listStates = List(3) { rememberLazyListState() }, selectedFilter = NotificationCategory.All, onSelectFilter = {},
                newNoticeMessage = null, onNewNoticeClick = null,
            )
        } }
        rule.onNodeWithText("投稿の本文はそのまま").assertDoesNotExist()
        rule.onNodeWithTag("notification_grouped_row").performTouchInput { click(topRight) }
        rule.runOnIdle { assertNull(opened.get()) }
        rule.onNodeWithTag("filter_reveal").performClick()
        rule.onNodeWithText("投稿の本文はそのまま").assertExists()
    }

    @Test fun pagesThatOnlyEnlargeTheSameGroupDoNotAutomaticallyDrainHistory() {
        val loads = AtomicInteger()
        val initial = event("initial")
        val state = mutableStateOf(NotificationsUiState(notifications = listOf(initial), isInitialPageLoaded = true, notificationsNextMaxId = "next"))
        rule.setContent { MastodonClientTheme {
            NotificationsContent(
                state = state.value, padding = PaddingValues(), onRefresh = {}, onLoadMore = {},
                onAutoLoadMore = {
                    val count = loads.incrementAndGet()
                    state.value = state.value.copy(notifications = state.value.notifications + event("older-$count"), notificationsNextMaxId = "page-$count")
                },
                onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {}, onReact = { _, _ -> },
                onAccountClick = {}, onMediaClick = { _, _ -> }, preferences = AppPreferences(groupNotifications = true),
                listStates = List(3) { rememberLazyListState() }, selectedFilter = NotificationCategory.All, onSelectFilter = {},
                newNoticeMessage = null, onNewNoticeClick = null,
            )
        } }
        rule.waitUntil { loads.get() > 0 }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, loads.get()) }
        rule.onNodeWithText("さらに読み込む").assertExists()
    }
}
