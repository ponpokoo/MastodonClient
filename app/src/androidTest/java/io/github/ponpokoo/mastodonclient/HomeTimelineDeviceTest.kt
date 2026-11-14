package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class HomeTimelineDeviceTest {
    @get:Rule(order = 0) val notificationPermission = NotificationPermissionRule()
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openFirstClickableTimelinePost() {
        // A tap at the card's center can land on an attachment or another nested control.
        composeRule.onAllNodes(hasTestTag("timeline_status") and hasClickAction() and
            hasAnyAncestor(hasTestTag("timeline_list")))[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun assertPostMenuShowsBrowserAction() {
        // For another author's post, the menu waits for the relationship request.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ブラウザーで開く", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("メニューを開けませんでした", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("ブラウザーで開く", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun dismissUpdateNoticeIfPresent() {
        if (composeRule.onAllNodesWithTag("update_notice_close").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("update_notice_close").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("update_notice").fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun selectMainTab(name: String) {
        composeRule.onNodeWithTag("main_tab_$name")
            .performSemanticsAction(SemanticsActions.OnClick)
            .assertIsSelected()
    }

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

        dismissUpdateNoticeIfPresent()
        openFirstClickableTimelinePost()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("投稿メニュー").performClick()
        assertPostMenuShowsBrowserAction()
    }

    @Test
    fun returningFromStatusDetailPreservesTimelinePosition() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("instance_input").fetchSemanticsNodes().isNotEmpty()
        }
        val isAuthenticated = composeRule.onAllNodesWithTag("timeline_list")
            .fetchSemanticsNodes()
            .isNotEmpty()
        assumeTrue("Device has no authenticated Mastodon session", isAuthenticated)

        dismissUpdateNoticeIfPresent()
        repeat(5) {
            composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        val scrollRange = composeRule.onNodeWithTag("timeline_list")
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assumeTrue("Timeline did not scroll", scrollRange.value() > 0f)

        openFirstClickableTimelinePost()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        val restoredScrollRange = composeRule.onNodeWithTag("timeline_list")
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Returning from detail reset the timeline to the top", restoredScrollRange.value() > 0f)
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

        dismissUpdateNoticeIfPresent()
        composeRule.onNodeWithContentDescription("アカウントを切り替える").performClick()
        composeRule.onNodeWithTag("account_switch_dialog").assertExists()
        composeRule.onNodeWithText("アカウントを切り替える").assertExists()
        composeRule.onNodeWithText("アカウントを追加").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("アカウント切替を閉じる").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("設定").performClick()
        composeRule.onNodeWithTag("settings_category_Appearance").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("テーマ", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_category_Accounts"))
        composeRule.onNodeWithTag("settings_category_Accounts").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("アカウントを追加").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty()
        }

        dismissUpdateNoticeIfPresent()
        composeRule.onAllNodesWithContentDescription("投稿メニュー")[0].performClick()
        assertPostMenuShowsBrowserAction()
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

        openFirstClickableTimelinePost()
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

        selectMainTab("notifications")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("notifications_screen").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("notification_status_quote").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithTag("notification_status_quote")[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("status_detail").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        selectMainTab("home")
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

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        selectMainTab("home")
        composeRule.waitForIdle()

        selectMainTab("notifications")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("notifications_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("notification_filter_mentions")
            .performSemanticsAction(SemanticsActions.OnClick)
            .assertIsSelected()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("notifications_list_mentions").fetchSemanticsNodes().isNotEmpty()
        }

        selectMainTab("home")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_list").fetchSemanticsNodes().isNotEmpty()
        }

        selectMainTab("profile")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("profile_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("プロフィールのその他メニュー")
            .performSemanticsAction(SemanticsActions.OnClick)
        val profileMenuItem = hasAnyAncestor(isPopup()) and hasClickAction()
        composeRule.onNode(profileMenuItem and hasText("ブックマーク")).performScrollTo().assertIsDisplayed()
        composeRule.onNode(profileMenuItem and hasText("お気に入り")).performScrollTo().assertIsDisplayed()
        composeRule.onNode(profileMenuItem and hasText("リスト")).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("saved_timelines_screen").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        selectMainTab("home")
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
