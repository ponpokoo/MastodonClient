package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.StatusMention
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalCustomReactionEmojiLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalFavouriteListOpener
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistoryLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistorySaver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusInteractionDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longPressingFavouriteOpensFavouritedAccountsWithoutTogglingFavourite() {
        val favourites = AtomicInteger()
        val listedStatusId = AtomicReference<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFavouriteListOpener provides listedStatusId::set) {
                    StatusCard(
                        status = status().copy(favouritesCount = 1),
                        onStatusClick = null,
                        onFavourite = { favourites.incrementAndGet() },
                        onUnavailableAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("お気に入り（長押しで一覧）")
            .performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals(0, favourites.get())
            assertEquals("status-1", listedStatusId.get())
        }
    }

    @Test
    fun remoteCustomReactionTapSendsDomainAndLongPressOpensAccounts() {
        val sentReaction = AtomicReference<String?>(null)
        val listedReaction = AtomicReference<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(reactions = listOf(EmojiReaction("custom", 2, false,
                        "https://example.social/custom.png", emptySet(), domain = "misskey.example"))),
                    onStatusClick = null,
                    onReact = sentReaction::set,
                    onReactionLongPress = { listedReaction.set(it.name) },
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("displayed_reaction").performClick()
        composeRule.runOnIdle { assertEquals("custom@misskey.example", sentReaction.get()) }
        sentReaction.set(null)
        composeRule.onNodeWithTag("displayed_reaction").performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals(null, sentReaction.get())
            assertEquals("custom", listedReaction.get())
        }
    }

    @Test
    fun pickerShowsServerCustomEmojiAndSendsShortcode() {
        val sentReaction = AtomicReference<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCustomReactionEmojiLoader provides {
                        Result.success(listOf(CustomEmoji("custom", "https://example.social/custom.png", "https://example.social/custom.png", "テスト")))
                    },
                    LocalReactionHistoryLoader provides { listOf("custom") },
                ) {
                    StatusCard(
                        status = status().copy(supportsEmojiReactions = true),
                        onStatusClick = null,
                        onReact = sentReaction::set,
                        onUnavailableAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("リアクション").performClick()
        composeRule.onNodeWithText("履歴").assertExists()
        composeRule.onNodeWithTag("reaction_search").performTextInput(":custom:")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(":custom:").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(":custom:").performClick()
        composeRule.runOnIdle { assertEquals("custom", sentReaction.get()) }
    }

    @Test
    fun longPressingEmojiHistoryRequiresConfirmationBeforeDeletion() {
        val saved = AtomicReference(listOf("👍"))
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalReactionHistoryLoader provides { saved.get() },
                    LocalReactionHistorySaver provides { saved.set(it) },
                ) {
                    StatusCard(
                        status = status().copy(supportsEmojiReactions = true),
                        onStatusClick = null,
                        onReact = {},
                        onUnavailableAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("リアクション").performClick()
        composeRule.onAllNodesWithText("👍")[0].performTouchInput { longClick() }
        composeRule.onNodeWithText("履歴から削除").assertExists()
        composeRule.runOnIdle { assertEquals(listOf("👍"), saved.get()) }
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.runOnIdle { assertEquals(listOf("👍"), saved.get()) }

        composeRule.onAllNodesWithText("👍")[0].performTouchInput { longClick() }
        composeRule.onNodeWithText("削除").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { saved.get().isEmpty() }
    }

    @Test
    fun contentWarningHidesBodyAndPreviewUntilExpanded() {
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(
                        spoilerText = "映画の結末",
                        contentHtml = "<p>結末の本文</p>",
                        previewCard = PreviewCard("https://example.social/article", "記事の見出し", "説明",
                            "link", "", null, null),
                    ),
                    onStatusClick = null,
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithText("CW").assertExists()
        composeRule.onNodeWithText("映画の結末").assertExists()
        composeRule.onNodeWithText("結末の本文").assertDoesNotExist()
        composeRule.onNodeWithText("記事の見出し").assertDoesNotExist()
        composeRule.onNodeWithText("内容を表示").performClick()
        composeRule.onNodeWithText("結末の本文").assertExists()
        composeRule.onNodeWithText("記事の見出し").assertExists()
    }

    @Test
    fun reactionsWrapOntoMultipleRows() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    StatusCard(
                        status = status().copy(reactions = (1..6).map {
                            EmojiReaction("reaction$it", 1, false, null, emptySet())
                        }),
                        onStatusClick = null,
                        onUnavailableAction = {},
                    )
                }
            }
        }

        val positions = composeRule.onAllNodesWithTag("displayed_reaction").fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        assertEquals(6, positions.size)
        assertTrue(positions.distinct().size > 1)
    }

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
    fun followersOnlyBoostIsUnavailableInTimelineAndPostDetail() {
        val boosts = AtomicInteger()
        val isDetail = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(visibility = "private"),
                    onStatusClick = null,
                    onBoost = { boosts.incrementAndGet() },
                    onUnavailableAction = {},
                    fullWidthContent = isDetail.value,
                )
            }
        }
        composeRule.onNodeWithContentDescription("この公開範囲ではブーストできません").assertIsNotEnabled()
        composeRule.runOnIdle { isDetail.value = true }
        composeRule.onNodeWithContentDescription("この公開範囲ではブーストできません").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, boosts.get()) }
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
