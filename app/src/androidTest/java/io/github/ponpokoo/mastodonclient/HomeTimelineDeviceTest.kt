package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class HomeTimelineDeviceTest {
    @get:Rule(order = 0) val notificationPermission = NotificationPermissionRule()
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun statusDetailHasTheSamePostMenu() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("instance_input").fetchSemanticsNodes().isNotEmpty()
        }
        assumeTrue(
            "Device has no authenticated Mastodon session",
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty(),
        )

        composeRule.onAllNodesWithTag("timeline_status")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("投稿メニュー").performClick()
        composeRule.onNodeWithText("ブラウザで開く").assertExists()
    }

    @Test
    fun refreshesAndScrollsAuthenticatedHomeTimeline() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("instance_input").fetchSemanticsNodes().isNotEmpty()
        }
        val isAuthenticated = composeRule.onAllNodesWithTag("timeline_list")
            .fetchSemanticsNodes()
            .isNotEmpty()
        assumeTrue("Device has no authenticated Mastodon session", isAuthenticated)

        composeRule.onNodeWithContentDescription("アカウントを切り替える").performClick()
        composeRule.onNodeWithTag("account_switch_dialog").assertExists()
        composeRule.onNodeWithText("アカウントを切り替える").assertExists()
        composeRule.onNodeWithText("アカウントを追加").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("アカウント切替を閉じる").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("設定").performClick()
        composeRule.onNodeWithTag("settings_screen").assertExists()
        composeRule.onNodeWithText("テーマ").assertExists()
        repeat(8) {
            if (composeRule.onAllNodesWithText("アカウント管理").fetchSemanticsNodes().isEmpty()) {
                composeRule.onNodeWithTag("settings_screen").performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        composeRule.onNodeWithText("アカウント管理").assertExists()
        composeRule.onNodeWithText("アカウントを追加").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithContentDescription("投稿メニュー")[0].performClick()
        composeRule.onNodeWithText("ブラウザで開く").assertExists()
        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("status_author_avatar")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("profile_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        if (composeRule.onAllNodesWithTag("media_attachment").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithTag("media_attachment")[0].performClick()
            composeRule.onNodeWithTag("media_viewer").assertExists()
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
            }
        }

        composeRule.onAllNodesWithTag("timeline_status")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("status_detail").assertExists()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("main_tab_notifications").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("notifications_screen").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("notification_status_quote").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithTag("notification_status_quote")[0].performClick()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        repeat(8) {
            composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().also {
            assumeTrue("No timeline statuses visible after scrolling", it.isNotEmpty())
        }

        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline_list").assertExists()

        composeRule.onNodeWithTag("main_tab_notifications").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("notifications_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("notification_filter_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("notification_filter_mentions").assertIsSelected()

        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("main_tab_profile").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("profile_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
        composeRule.onNodeWithText("ブックマーク").assertExists()
        composeRule.onNodeWithText("お気に入り").assertExists()
        composeRule.onNodeWithText("リスト").performClick()
        composeRule.onNodeWithTag("saved_timelines_screen").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("新規投稿").performClick()
        composeRule.onNodeWithTag("compose_post").assertExists()
        composeRule.onNodeWithTag("compose_account_switcher").assertExists()
        composeRule.onNodeWithTag("compose_visibility").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}
