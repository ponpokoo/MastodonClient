package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class HomeTimelineDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty()
        }

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

        repeat(8) {
            composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().also {
            assumeTrue("No timeline statuses visible after scrolling", it.isNotEmpty())
        }

        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_screen").assertExists()

        composeRule.onNodeWithTag("main_tab_home").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("新規投稿").performClick()
        composeRule.onNodeWithTag("compose_post").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}
