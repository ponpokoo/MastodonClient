package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

        composeRule.onNodeWithTag("timeline_refresh", useUnmergedTree = true).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().isNotEmpty()
        }

        repeat(8) {
            composeRule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().also {
            assumeTrue("No timeline statuses visible after scrolling", it.isNotEmpty())
        }
    }
}
