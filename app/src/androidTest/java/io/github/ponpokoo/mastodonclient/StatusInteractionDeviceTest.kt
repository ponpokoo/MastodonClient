package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalCustomReactionEmojiLoader
import io.github.ponpokoo.mastodonclient.feature.status.LocalFavouriteListOpener
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistoryLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistorySaver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusInteractionDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    private fun showContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities provides
                    io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities(
                        reactionApi = io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi.Fedibird),
            ) { content() }
        }
    }

    @Test fun sharedServerCapabilitiesControlActionsEvenWhenPostFieldsAreAbsent() {
        val capabilities = mutableStateOf(io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities())
        var reactions = 0
        showContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities provides capabilities.value,
                ) {
                    StatusCard(status = status().copy(reactions = listOf(EmojiReaction("👍", 1, false, null, emptySet()))),
                        onStatusClick = null, onReact = { reactions++ }, onUnavailableAction = {})
                }
            }
        }
        composeRule.onNodeWithContentDescription("リアクション").assertDoesNotExist()
        composeRule.onNodeWithTag("displayed_reaction").assertHasNoClickAction()
        composeRule.runOnIdle {
            capabilities.value = io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities(
                supportsEmojiReactions = true, reactionApi = io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi.Pleroma)
        }
        composeRule.onNodeWithContentDescription("リアクション").assertDoesNotExist()
        composeRule.runOnIdle {
            capabilities.value = io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities(
                reactionApi = io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi.Fedibird)
        }
        composeRule.onNodeWithContentDescription("リアクション").assertExists()
        composeRule.onNodeWithTag("displayed_reaction").assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(1, reactions)
            capabilities.value = io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities(
                reactionApi = io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi.Unsupported)
        }
        composeRule.onNodeWithContentDescription("リアクション").assertDoesNotExist()
        composeRule.onNodeWithTag("displayed_reaction").assertHasNoClickAction()
    }

    @Test fun visibilityHeaderKeepsMenuReachableWithLargeTextAndLongAuthorNames() {
        val scale = mutableStateOf(1f)
        val theme = mutableStateOf(ThemeMode.Light)
        val visibility = mutableStateOf("public")
        var menus = 0
        showContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MastodonClientTheme(themeMode = theme.value) {
                    Surface {
                        StatusCard(
                            status = status().copy(visibility = visibility.value,
                                author = status().author.copy(displayName = "長い表示名を持つテストユーザー", accountName = "long-account-name@example.test")),
                            onStatusClick = null,
                            onMoreClick = { menus++ },
                            onUnavailableAction = {},
                        )
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                for ((value, label) in listOf("public" to "公開", "unlisted" to "ひかえめな公開",
                    "private" to "フォロワー限定", "direct" to "指定した相手のみ")) {
                    composeRule.runOnIdle { theme.value = mode; scale.value = fontScale; visibility.value = value }
                    val icon = composeRule.onNodeWithContentDescription(label, useUnmergedTree = true)
                    icon.assertIsDisplayed()
                    val menu = composeRule.onNodeWithContentDescription("投稿メニュー")
                    menu.assertIsDisplayed()
                    assertTrue("Visibility overlaps menu at $mode / $fontScale / $value",
                        icon.fetchSemanticsNode().boundsInRoot.right <= menu.fetchSemanticsNode().boundsInRoot.left)
                    menu.performTouchInput { click(center) }
                    if (value == "public") {
                        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
                        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                            "status-header-${mode.name.lowercase()}-$fontScale.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { assertEquals(24, menus) }
    }

    @Test
    fun reactionVisibilityCanBeToggledWithoutHidingTheActionButton() {
        val display = mutableStateOf(io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences())
        showContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(
                        reactions = listOf(EmojiReaction("👍", 2, false, null, emptySet()))),
                    onStatusClick = null,
                    onReact = {},
                    onUnavailableAction = {},
                    displayPreferences = display.value,
                )
            }
        }
        composeRule.onNodeWithTag("displayed_reaction").assertExists()
        composeRule.runOnIdle { display.value = display.value.copy(showReactions = false) }
        composeRule.onNodeWithTag("displayed_reaction").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("リアクション").assertExists()
        composeRule.runOnIdle { display.value = display.value.copy(showReactions = true) }
        composeRule.onNodeWithTag("displayed_reaction").assertExists()
    }

    @Test
    fun pressingActionCountHighlightsOnlyTheIconAndCancelClearsIt() {
        val theme = mutableStateOf(ThemeMode.Light)
        val favourites = AtomicInteger()
        showContent {
            MastodonClientTheme(themeMode = theme.value) {
                Surface {
                    StatusCard(
                        status = status().copy(favouritesCount = 12),
                        onStatusClick = null,
                        onFavourite = { favourites.incrementAndGet() },
                        onUnavailableAction = {},
                    )
                }
            }
        }

        for ((index, mode) in listOf(ThemeMode.Light, ThemeMode.Dark).withIndex()) {
            composeRule.runOnIdle { theme.value = mode }
            val action = composeRule.onNodeWithContentDescription("お気に入り")
            val count = composeRule.onNodeWithText("12", useUnmergedTree = true)
            val before = action.captureToImage().toPixelMap()
            val countBefore = count.captureToImage().toPixelMap()
            composeRule.mainClock.autoAdvance = false
            try {
                // The count remains part of the button, but must not receive its highlight.
                count.performTouchInput { down(center) }
                composeRule.mainClock.advanceTimeByFrame()
                val image = action.captureToImage()
                val pressed = image.toPixelMap()
                val changed = (0 until before.width).flatMap { x ->
                    (0 until before.height).filter { y -> before[x, y] != pressed[x, y] }.map { y -> x to y }
                }
                assertTrue("Pressing the count must highlight the icon in $mode", changed.isNotEmpty())
                val left = changed.minOf { it.first }
                val right = changed.maxOf { it.first }
                val top = changed.minOf { it.second }
                val bottom = changed.maxOf { it.second }
                assertTrue("Highlight must stay circular in $mode",
                    kotlin.math.abs((right - left) - (bottom - top)) <= 2)
                for (x in listOf(left, right)) {
                    for (y in listOf(top, bottom)) {
                        assertEquals("Circle corners must stay clear in $mode", before[x, y], pressed[x, y])
                    }
                }
                val countPressed = count.captureToImage().toPixelMap()
                assertTrue("Count must stay outside the highlight in $mode",
                    (0 until countBefore.width).all { x ->
                        (0 until countBefore.height).all { y -> countBefore[x, y] == countPressed[x, y] }
                    })
                File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "status-action-pressed-${mode.name.lowercase()}.png").outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                count.performTouchInput { cancel() }
                composeRule.mainClock.advanceTimeByFrame()
                val canceled = action.captureToImage().toPixelMap()
                assertTrue("Cancel must clear the highlight in $mode",
                    (0 until before.width).all { x ->
                        (0 until before.height).all { y -> before[x, y] == canceled[x, y] }
                    })
                composeRule.runOnIdle { assertEquals(index, favourites.get()) }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            count.performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(index + 1, favourites.get()) }
        }
    }

    @Test
    fun longPressingFavouriteOpensFavouritedAccountsWithoutTogglingFavourite() {
        val favourites = AtomicInteger()
        val listedStatusId = AtomicReference<String?>(null)
        showContent {
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
        showContent {
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
        showContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCustomReactionEmojiLoader provides {
                        Result.success(listOf(CustomEmoji("custom", "https://example.social/custom.png", "https://example.social/custom.png", "テスト")))
                    },
                    LocalReactionHistoryLoader provides { listOf("custom") },
                ) {
                    StatusCard(
                        status = status(),
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
        showContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalReactionHistoryLoader provides { saved.get() },
                    LocalReactionHistorySaver provides { saved.set(it) },
                ) {
                    StatusCard(
                        status = status(),
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
        showContent {
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
        composeRule.onNodeWithText("内容を隠す").performClick()
        composeRule.onNodeWithText("結末の本文").assertDoesNotExist()
        composeRule.onNodeWithText("記事の見出し").assertDoesNotExist()
    }

    @Test
    fun reactionsWrapOntoMultipleRows() {
        showContent {
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
        showContent {
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
        val quoteMode = AtomicReference<QuoteMode?>(null)
        showContent {
            MaterialTheme {
                StatusCard(
                    status = status(),
                    onStatusClick = null,
                    onBoost = { boosts.incrementAndGet() },
                    onQuote = quoteMode::set,
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用").performClick()

        composeRule.runOnIdle {
            assertEquals(0, boosts.get())
            assertEquals(QuoteMode.Native, quoteMode.get())
        }
        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用（リンク）").performClick()
        composeRule.runOnIdle {
            assertEquals(0, boosts.get())
            assertEquals(QuoteMode.Link, quoteMode.get())
        }
    }

    @Test
    fun legacyServerOffersOnlyLinkQuote() {
        val quoteMode = AtomicReference<QuoteMode?>(null)
        showContent {
            MaterialTheme {
                StatusCard(status = status().copy(quoteApproval = null), onStatusClick = null,
                    onQuote = quoteMode::set, onUnavailableAction = {})
            }
        }
        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用").assertDoesNotExist()
        composeRule.onNodeWithText("引用（リンク）").performClick()
        composeRule.runOnIdle { assertEquals(QuoteMode.Link, quoteMode.get()) }
    }

    @Test
    fun nativeQuoteRespectsApprovalWhileLinkQuoteRemainsAvailable() {
        val approval = mutableStateOf("manual")
        val quoteMode = AtomicReference<QuoteMode?>(null)
        showContent {
            MaterialTheme {
                StatusCard(status = status().copy(quoteApproval = approval.value), onStatusClick = null,
                    onQuote = quoteMode::set, onUnavailableAction = {})
            }
        }
        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用（承認申請）").performClick()
        composeRule.runOnIdle {
            assertEquals(QuoteMode.Native, quoteMode.get())
            approval.value = "denied"
            quoteMode.set(null)
        }
        composeRule.onNodeWithContentDescription("ブースト（長押しで引用を選択）")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("引用").assertIsNotEnabled()
        composeRule.onNodeWithText("引用（リンク）").performClick()
        composeRule.runOnIdle { assertEquals(QuoteMode.Link, quoteMode.get()) }
    }

    @Test
    fun followersOnlyBoostIsUnavailableInTimelineAndPostDetail() {
        val boosts = AtomicInteger()
        val isDetail = mutableStateOf(false)
        showContent {
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
        showContent {
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

    @Test
    fun selfReplyShowsReplyTargetAndOpensItsAccount() {
        val openedAccount = AtomicReference<String?>(null)
        showContent {
            MaterialTheme {
                StatusCard(
                    status = status().copy(
                        inReplyToId = "parent-status",
                        inReplyToAccountId = "author",
                    ),
                    onStatusClick = null,
                    onAuthorClick = openedAccount::set,
                    onUnavailableAction = {},
                )
            }
        }

        composeRule.onNodeWithText("返信先: @bob@social.example").performClick()
        composeRule.runOnIdle { assertEquals("author", openedAccount.get()) }
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
