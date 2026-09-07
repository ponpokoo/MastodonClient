package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class InstanceDiscoveryDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun discoversPublicMastodonInstance() {
        composeRule.onNodeWithTag("instance_input").performTextInput("mastodon.social")
        composeRule.onNodeWithTag("discover_button").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("instance_success")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
