package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.runOnIdle { assertTrue(closed.get()) }
    }
}
