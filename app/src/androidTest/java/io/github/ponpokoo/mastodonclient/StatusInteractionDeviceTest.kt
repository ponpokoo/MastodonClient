package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.StatusMention
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatusInteractionDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tappingBoostedByOpensBoosterNotStatus() {
        val openedAccount = AtomicReference<String?>(null)
        val openedStatus = AtomicReference<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(boostedBy = StatusAuthor("booster", "Alice", "alice@social.example", "")),
                    onStatusClick = openedStatus::set,
                    onAuthorClick = openedAccount::set,
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("status_booster").performClick()
        composeRule.runOnIdle {
            assertEquals("booster", openedAccount.get())
            assertEquals(null, openedStatus.get())
        }
    }

    @Test
    fun longPressingBoostOffersQuoteWithoutBoosting() {
        val boosts = AtomicInteger()
        val quotes = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status(),
                    onStatusClick = null,
                    onBoost = { boosts.incrementAndGet() },
                    onQuote = { quotes.incrementAndGet() },
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用").performClick()

        composeRule.runOnIdle {
            assertEquals(0, boosts.get())
            assertEquals(1, quotes.get())
        }
    }

    @Test
    fun tappingMentionUsesItsAccountId() {
        val openedAccount = AtomicReference<String?>(null)
        val openedUrl = AtomicReference<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(
                        contentHtml = "<p>Hello <a href=\"https://social.example/@alice\">@alice</a></p>",
                        mentions = listOf(StatusMention("account-42", "alice@social.example", "https://social.example/@alice")),
                    ),
                    onStatusClick = null,
                    onAuthorClick = openedAccount::set,
                    onOpenLink = openedUrl::set,
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithText("@alice", substring = true).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals("account-42", openedAccount.get())
            assertEquals(null, openedUrl.get())
        }
    }

    private fun status() = TimelineStatus(
        timelineId = "status-1",
        statusId = "status-1",
        createdAt = "2026-09-09T00:00:00Z",
        author = StatusAuthor("author", "Bob", "bob@social.example", ""),
        boostedBy = null,
        contentHtml = "<p>Hello</p>",
        spoilerText = "",
        sensitive = false,
        visibility = "public",
        url = "https://social.example/@bob/1",
        repliesCount = 0,
        boostsCount = 0,
        favouritesCount = 0,
        mediaAttachments = emptyList(),
        quoteApproval = "automatic",
    )
}
