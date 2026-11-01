package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso.pressBack
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MediaViewerDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsFullScreenImageViewerAndHandlesClose() {
        val closed = AtomicBoolean(false)
        composeRule.setContent {
            MediaViewerScreen(
                media = listOf(MediaAttachment(
                    id = "test-image",
                    type = "image",
                    url = "https://example.com/image.jpg",
                    previewUrl = null,
                    description = "テスト画像",
                )),
                initialIndex = 0,
                onBack = { closed.set(true) },
            )
        }

        composeRule.onNodeWithTag("media_viewer").assertExists()
        composeRule.onNodeWithContentDescription("画像一覧を開く").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.runOnIdle { assertTrue(closed.get()) }
    }

    @Test
    fun overviewStartsAtCurrentImageScrollsSelectsAndReturnsWithoutClosing() {
        val closed = AtomicBoolean(false)
        // Missing local files also exercise selection when thumbnails cannot be loaded.
        val media = (1..24).map { index ->
            MediaAttachment("image-$index", "image", "file:///missing/image-$index.jpg", null, "画像$index")
        }
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 600.dp)) {
                    MediaViewerScreen(media, 18, { closed.set(true) })
                }
            }
        }
        composeRule.onNodeWithContentDescription("画像一覧を開く").performClick()
        composeRule.onNodeWithText("一覧").assertIsDisplayed()
        composeRule.onNodeWithTag("media_overview_image_18").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("media_image_overview").performTouchInput { swipeDown() }
        composeRule.runOnIdle { assertFalse(closed.get()) }
        composeRule.onNodeWithTag("media_image_overview").performScrollToIndex(23)
        composeRule.onNodeWithTag("media_overview_image_23").performClick()
        composeRule.onNodeWithTag("media_image_overview").assertDoesNotExist()
        composeRule.onNodeWithText("24 / 24").assertExists()
        composeRule.onNodeWithContentDescription("画像一覧を開く").performClick()
        composeRule.onNodeWithTag("media_overview_image_23").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription("画像表示に戻る").performClick()
        composeRule.onNodeWithText("24 / 24").assertExists()
        composeRule.onNodeWithContentDescription("画像一覧を開く").performClick()
        pressBack()
        composeRule.onNodeWithTag("media_image_overview").assertDoesNotExist()
        composeRule.onNodeWithText("24 / 24").assertExists()
        composeRule.runOnIdle { assertFalse(closed.get()) }
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.runOnIdle { assertTrue(closed.get()) }
    }
}
