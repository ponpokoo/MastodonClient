package io.github.ponpokoo.mastodonclient

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionHistoryLoader
import io.github.ponpokoo.mastodonclient.feature.timeline.ReactionPickerSheet
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReactionPickerCategoryDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun switchingBetweenDenseAndEmptyCategoriesKeepsSheetAndSearchInPlace() {
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = {},
                    customEmojisOverride = (0 until 80).map { emoji("custom_$it", "多数") })
            }
        }
        val sheet = composeRule.onNodeWithTag("reaction_picker_sheet").fetchSemanticsNode().boundsInWindow
        val search = composeRule.onNodeWithTag("reaction_search").fetchSemanticsNode().boundsInWindow
        for (category in listOf("履歴", "お気に入り", "標準", "表情", "カスタム · 多数", "すべて")) {
            chooseCategory(category)
            val updatedSheet = composeRule.onNodeWithTag("reaction_picker_sheet").fetchSemanticsNode().boundsInWindow
            val updatedSearch = composeRule.onNodeWithTag("reaction_search").fetchSemanticsNode().boundsInWindow
            assertEquals("Sheet height changed for $category", sheet.height, updatedSheet.height, 1f)
            assertEquals("Sheet position changed for $category", sheet.top, updatedSheet.top, 1f)
            assertEquals("Search position changed for $category", search.top, updatedSearch.top, 1f)
        }
    }

    @Test fun standardCategoryFiltersWithoutSelectingAnEmoji() {
        var selection: String? = null
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = { selection = it },
                    customEmojisOverride = emptyList())
            }
        }
        savePreview("all")
        composeRule.onNodeWithContentDescription("カテゴリ：表情").performClick().assertIsSelected()
        composeRule.onNodeWithText("😊").assertIsDisplayed()
        composeRule.onNodeWithText("👍").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(null, selection) }
        savePreview("feelings")
        composeRule.onNodeWithText("😊").performClick()
        composeRule.runOnIdle { assertEquals("😊", selection) }
    }

    @Test fun searchAndCustomCategoryIntersectAndAllRestoresOtherMatches() {
        var selection: String? = null
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = { selection = it },
                    customEmojisOverride = listOf(emoji("shared_first", "気持ち"), emoji("shared_second", "別カテゴリ")))
            }
        }
        chooseCategory("カスタム · 気持ち")
        composeRule.onNodeWithTag("reaction_search").performTextInput("shared")
        composeRule.onNodeWithContentDescription(":shared_first:").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(":shared_second:").assertDoesNotExist()
        composeRule.onNodeWithText("😊").assertDoesNotExist()
        composeRule.onNodeWithTag("reaction_search").performTextReplacement("missing")
        composeRule.onNodeWithText("該当する絵文字がありません").assertIsDisplayed()
        composeRule.onNodeWithTag("reaction_search").performTextReplacement("shared")
        chooseCategory("すべて")
        composeRule.onNodeWithContentDescription(":shared_second:").performClick()
        composeRule.runOnIdle { assertEquals("shared_second", selection) }
    }

    @Test fun historySearchPreservesEntriesNoLongerInTheServerEmojiList() {
        var selection: String? = null
        composeRule.setContent {
            MastodonClientTheme {
                CompositionLocalProvider(LocalReactionHistoryLoader provides { listOf("👍", "old_custom") }) {
                    ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = { selection = it },
                        customEmojisOverride = emptyList())
                }
            }
        }
        composeRule.onNodeWithContentDescription("カテゴリ：履歴").performClick()
        composeRule.onNodeWithText("👍").assertIsDisplayed()
        composeRule.onNodeWithText("😊").assertDoesNotExist()
        composeRule.onNodeWithTag("reaction_search").performTextInput("old")
        composeRule.onNodeWithText("👍").assertDoesNotExist()
        composeRule.onNodeWithText("old_custom").performClick()
        composeRule.runOnIdle { assertEquals("old_custom", selection) }
    }

    @Test fun missingCategoryMetadataSharesOtherAndRemovedCategoryFallsBackToAll() {
        val emojis = mutableStateOf(listOf(emoji("uncategorized", null), emoji("blank_category", " ")))
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = {}, customEmojisOverride = emojis.value)
            }
        }
        chooseCategory("カスタム · その他")
        composeRule.onNodeWithContentDescription(":uncategorized:").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(":blank_category:").assertIsDisplayed()
        composeRule.runOnIdle { emojis.value = emptyList() }
        composeRule.onNodeWithTag("reaction_selected_category").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("カテゴリ：すべて").assertIsSelected()
        composeRule.onNodeWithText("👍").assertIsDisplayed()
    }

    @Test fun categoryListReachesCategoriesOutsideTheIconStripAndEmptyHistoryCanBeLeft() {
        val emojis = (0 until 20).map { emoji("custom_$it", "group_$it") }
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = {}, customEmojisOverride = emojis)
            }
        }
        composeRule.onNodeWithContentDescription("カテゴリ：履歴").performClick()
        composeRule.onNodeWithText("履歴はまだありません").assertIsDisplayed()
        chooseCategory("カスタム · group_19")
        composeRule.onNodeWithContentDescription(":custom_19:").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(":custom_0:").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("カテゴリ：カスタム · group_19").assertIsSelected()
    }

    @Test fun wideCategoryRepresentativeKeepsItsHeightAndOriginalAspectRatio() {
        val wide = imageEmoji("wide_representative", "横長", 320, 40)
        val square = imageEmoji("square_representative", "正方形", 40, 40)
        composeRule.setContent {
            MastodonClientTheme {
                ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = {},
                    customEmojisOverride = listOf(wide, square))
            }
        }
        chooseCategory("カスタム · 横長")
        val wideImage = composeRule.onNodeWithTag("reaction_category_image:custom:横長", useUnmergedTree = true)
        val squareImage = composeRule.onNodeWithTag("reaction_category_image:custom:正方形", useUnmergedTree = true)
        composeRule.waitUntil(5_000) {
            val bounds = wideImage.fetchSemanticsNode().boundsInRoot
            bounds.width > bounds.height * 7
        }
        val wideBounds = wideImage.fetchSemanticsNode().boundsInRoot
        val squareBounds = squareImage.fetchSemanticsNode().boundsInRoot
        assertEquals(8f, wideBounds.width / wideBounds.height, 0.1f)
        assertEquals(squareBounds.height, wideBounds.height, 1f)
        val categoryBounds = composeRule.onNodeWithContentDescription("カテゴリ：カスタム · 横長")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(categoryBounds.width >= wideBounds.width)
        composeRule.onNodeWithContentDescription("カテゴリ一覧").assertIsDisplayed()
        savePreview("wide-representative")
        // Scrolling back to standard categories and returning must retain the resolved width.
        chooseCategory("標準")
        chooseCategory("カスタム · 横長")
        assertEquals(wideBounds.width, wideImage.fetchSemanticsNode().boundsInRoot.width, 1f)
        composeRule.onNodeWithContentDescription("カテゴリ：カスタム · 横長").assertIsSelected()
    }

    private fun imageEmoji(shortcode: String, category: String, width: Int, height: Int): CustomEmoji {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$shortcode.png")
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 28f
        }
        android.graphics.Canvas(bitmap).drawText("WIDE REACTION", 6f, 30f, paint)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val url = android.net.Uri.fromFile(file).toString()
        return CustomEmoji(shortcode, url, url, category)
    }

    private fun chooseCategory(label: String) {
        composeRule.onNodeWithContentDescription("カテゴリ一覧").performClick()
        composeRule.onNodeWithTag("reaction_category_menu").performScrollToNode(hasText(label))
        composeRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("reaction_category_menu"))).performClick()
    }

    private fun savePreview(name: String) {
        val bitmap = composeRule.onNodeWithTag("reaction_picker_sheet").captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reaction-picker-previews")
            .apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun emoji(shortcode: String, category: String?) = CustomEmoji(shortcode, "", "", category)
}
