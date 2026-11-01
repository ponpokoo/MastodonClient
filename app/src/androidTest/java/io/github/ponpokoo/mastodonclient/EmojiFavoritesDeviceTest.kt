package io.github.ponpokoo.mastodonclient

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerHost
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerDataSource
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerEmojis
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class EmojiFavoritesDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val selected = AtomicReference<String?>(null)

    @Test fun changingSessionDiscardsSearchPreviewAndDelayedSaveFeedback() {
        val sessionKey = mutableStateOf("first-generation")
        val saveGate = CompletableDeferred<Unit>()
        val writeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val writeFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        rule.setContent {
            MastodonClientTheme {
                val first = sessionKey.value == "first-generation"
                val connection = rememberTestEmojiPickerConnection(EmojiPickerDataSource(
                    customEmojis = flowOf(EmojiPickerEmojis()),
                    loadFavorites = { if (first) emptyList() else listOf("🎉") },
                    updateFavorite = { name, _ ->
                        writeStarted.set(true)
                        withContext(NonCancellable) { saveGate.await() }
                        writeFinished.set(true)
                        listOf(name)
                    },
                ), sessionKey.value)
                EmojiPickerHost(connection, canUndo = false, onDismiss = {}, onSelected = selected::set)
            }
        }
        try {
            chooseCategory("標準")
            rule.onNodeWithTag("reaction_search").performTextInput("いいね")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("👍").performTouchInput { longClick() }
            rule.onNodeWithTag("reaction_emoji_preview").assertIsDisplayed()
            rule.onNodeWithText("お気に入りに追加").performClick()
            rule.waitUntil(5_000) { writeStarted.get() }
            rule.runOnIdle { sessionKey.value = "second-generation" }
            rule.onNodeWithTag("reaction_emoji_preview").assertDoesNotExist()
            rule.onNodeWithTag("reaction_search").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(""),
            ))
            chooseCategory("お気に入り")
            rule.onNodeWithText("🎉").assertIsDisplayed()
            rule.runOnIdle { saveGate.complete(Unit) }
            rule.waitUntil(5_000) { writeFinished.get() }
            rule.onNodeWithText("👍").assertDoesNotExist()
            rule.onNodeWithText("お気に入りに追加しました").assertDoesNotExist()
            rule.onNodeWithText("🎉").assertIsDisplayed()
        } finally { saveGate.complete(Unit) }
    }

    @Test fun standardPreviewAddsFavoritesAndReopeningAllowsConfirmedRemovalAndUndo() {
        val favorites = AtomicReference<List<String>>(emptyList())
        val visible = mutableStateOf(true)
        showPicker(favorites, visible = visible)
        chooseCategory("標準")
        rule.onNodeWithText("👍").performTouchInput { longClick() }
        rule.onNodeWithTag("reaction_emoji_preview").assertIsDisplayed()
        rule.onNodeWithText("閉じる").assertIsDisplayed()
        rule.onNodeWithText("お気に入りに追加").assertIsDisplayed()
        savePreview("standard-favorite-preview")
        rule.runOnIdle { assertNull(selected.get()) }
        rule.onNodeWithText("お気に入りに追加").performClick()
        rule.waitUntil(5_000) { favorites.get() == listOf("👍") }
        rule.runOnIdle { assertNull(selected.get()); visible.value = false }
        rule.runOnIdle { visible.value = true }
        chooseCategory("お気に入り")
        val favoriteSheet = rule.onNodeWithTag("reaction_picker_sheet").fetchSemanticsNode().boundsInWindow
        rule.onNodeWithText("👍").assertIsDisplayed().performTouchInput { longClick() }
        rule.onNodeWithText("お気に入りから削除").assertIsDisplayed()
        rule.onNodeWithText("キャンセル").performClick()
        rule.runOnIdle { assertEquals(listOf("👍"), favorites.get()) }
        rule.onNodeWithText("👍").performTouchInput { longClick() }
        rule.onNodeWithText("削除").performClick()
        rule.waitUntil(5_000) { favorites.get().isEmpty() }
        rule.onNodeWithText("お気に入りはまだありません").assertIsDisplayed()
        val emptySheet = rule.onNodeWithTag("reaction_picker_sheet").fetchSemanticsNode().boundsInWindow
        assertEquals(favoriteSheet.height, emptySheet.height, 1f)
        assertEquals(favoriteSheet.top, emptySheet.top, 1f)
        rule.onNodeWithText("元に戻す").performClick()
        rule.waitUntil(5_000) { favorites.get() == listOf("👍") }
        chooseCategory("標準")
        rule.onNodeWithText("👍").performTouchInput { longClick() }
        rule.onNodeWithText("お気に入り登録済み").assertIsNotEnabled()
        rule.onNodeWithText("閉じる").performClick()
        chooseCategory("お気に入り")
        rule.onNodeWithText("👍").performClick()
        rule.runOnIdle { assertEquals("👍", selected.get()) }
    }

    @Test fun customPreviewAddsShortcodeAndFavoriteSearchSelectsIt() {
        val favorites = AtomicReference<List<String>>(emptyList())
        val custom = customFixture()
        showPicker(favorites, customEmojis = listOf(custom))
        chooseCategory("カスタム · テスト")
        rule.onNodeWithContentDescription(":custom:").performTouchInput { longClick() }
        savePreview("custom-favorite-preview")
        rule.onNodeWithText("お気に入りに追加").performClick()
        rule.waitUntil(5_000) { favorites.get() == listOf("custom") }
        rule.runOnIdle { assertNull(selected.get()) }
        chooseCategory("お気に入り")
        rule.onNodeWithTag("reaction_search").performTextInput(":custom:")
        rule.onNodeWithContentDescription(":custom:").performClick()
        rule.runOnIdle { assertEquals("custom", selected.get()) }
    }

    @Test fun failedAdditionKeepsPreviewAndFavoritesUnchanged() {
        val favorites = AtomicReference<List<String>>(emptyList())
        showPicker(favorites, updater = { _, _ -> throw java.io.IOException("Fixture write failure") })
        chooseCategory("表情")
        rule.onNodeWithText("😊").performTouchInput { longClick() }
        rule.onNodeWithText("お気に入りに追加").performClick()
        rule.onNodeWithTag("reaction_emoji_preview").assertIsDisplayed()
        rule.onNode(hasText("お気に入りを保存できませんでした") and
            hasAnyAncestor(hasTestTag("reaction_emoji_preview"))).assertIsDisplayed()
        rule.runOnIdle { assertTrue(favorites.get().isEmpty()); assertNull(selected.get()) }
        rule.onNodeWithText("閉じる").performClick()
        chooseCategory("お気に入り")
        rule.onNodeWithText("再試行").performClick()
        rule.onNodeWithText("お気に入りはまだありません").assertIsDisplayed()
    }

    @Test fun failedReadCanBeRetriedWithoutReplacingSavedFavorites() {
        val favorites = AtomicReference(listOf("😊"))
        var attempts = 0
        showPicker(favorites, loader = {
            attempts++
            if (attempts == 1) throw java.io.IOException("Fixture read failure")
            favorites.get()
        })
        chooseCategory("お気に入り")
        rule.onNodeWithText("お気に入りを読み込めませんでした").assertIsDisplayed()
        rule.onNodeWithText("再試行").performClick()
        rule.onNodeWithText("😊").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("😊"), favorites.get()) }
    }

    private fun showPicker(
        favorites: AtomicReference<List<String>>,
        customEmojis: List<CustomEmoji> = emptyList(),
        visible: State<Boolean>? = null,
        loader: suspend () -> List<String> = { favorites.get() },
        updater: suspend (String, Boolean) -> List<String> = { name, favorite ->
            favorites.updateAndGet { if (favorite) (it + name).distinct() else it - name }
        },
    ) {
        rule.setContent {
            MastodonClientTheme {
                if (visible?.value != false) {
                    val connection = rememberTestEmojiPickerConnection(EmojiPickerDataSource(
                        customEmojis = flowOf(EmojiPickerEmojis(customEmojis)),
                        loadFavorites = loader, updateFavorite = updater,
                    ))
                    EmojiPickerHost(connection, canUndo = false, onDismiss = {}, onSelected = selected::set)
                }
            }
        }
    }

    private fun chooseCategory(label: String) {
        rule.onNodeWithContentDescription("カテゴリ一覧").performClick()
        rule.onNodeWithTag("reaction_category_menu").performScrollToNode(hasText(label))
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("reaction_category_menu"))).performClick()
    }

    private fun savePreview(name: String) {
        val bitmap = rule.onNodeWithTag("reaction_emoji_preview").captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reaction-picker-previews")
            .apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun customFixture(): CustomEmoji {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "favorite-custom.png")
        val bitmap = android.graphics.Bitmap.createBitmap(480, 80, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.MAGENTA; textSize = 40f
        }
        android.graphics.Canvas(bitmap).drawText("CUSTOM EMOJI", 8f, 54f, paint)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val url = android.net.Uri.fromFile(file).toString()
        return CustomEmoji("custom", url, url, "テスト")
    }
}
