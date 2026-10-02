package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaThumbnailDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val files = mutableListOf<File>()

    @After fun cleanup() { files.forEach { it.delete() } }

    @Test
    fun tallImageWithoutMetadataShowsBothEndsAndResizesWhenImageChanges() {
        val portrait = image(120, 600)
        val landscape = image(600, 120)
        val media = mutableStateOf(attachment(portrait))
        val size = mutableStateOf(ThumbnailSize.Compact)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    StatusCard(status(listOf(media.value)), onStatusClick = null, onUnavailableAction = {},
                        displayPreferences = TimelineDisplayPreferences(thumbnailSize = size.value))
                }
            }
        }
        val node = rule.onNodeWithTag("media_attachment")
        waitForRatio(node, 0.2f)
        assertEndsVisible(node)
        val compactHeight = node.fetchSemanticsNode().boundsInRoot.height
        rule.runOnIdle { size.value = ThumbnailSize.Standard }
        val standardBounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(standardBounds.height > compactHeight)
        assertTrue(standardBounds.width < 360 * rule.density.density)
        assertEquals(0.2f, standardBounds.width / standardBounds.height, 0.02f)
        assertEndsVisible(node)

        rule.runOnIdle { media.value = attachment(landscape) }
        waitForRatio(node, 5f)
        assertEndsVisible(node)
    }

    @Test
    fun phoneScreenshotsStayTogetherAndFitANarrowerPost() {
        val media = listOf(attachment(image(180, 420)), attachment(image(180, 420)))
        val width = mutableStateOf(360.dp)
        val size = mutableStateOf(ThumbnailSize.Compact)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(width.value)) {
                    StatusCard(status(media), onStatusClick = null, onUnavailableAction = {},
                        displayPreferences = TimelineDisplayPreferences(thumbnailSize = size.value))
                }
            }
        }
        val nodes = rule.onAllNodesWithTag("media_attachment")
        repeat(2) { index ->
            waitForRatio(nodes[index], 180f / 420)
            assertEndsVisible(nodes[index])
        }
        val compactHeight = nodes[0].fetchSemanticsNode().boundsInRoot.height
        rule.runOnIdle { size.value = ThumbnailSize.Standard }
        val standardBounds = nodes[0].fetchSemanticsNode().boundsInRoot
        val secondBounds = nodes[1].fetchSemanticsNode().boundsInRoot
        assertTrue(standardBounds.height > compactHeight)
        assertEquals(standardBounds.height, secondBounds.height, 1f)
        val gap = secondBounds.left - standardBounds.right
        assertTrue("Screenshots should be adjacent", gap > 0 && gap < standardBounds.width / 4)

        rule.runOnIdle { width.value = 240.dp }
        val narrowFirst = nodes[0].fetchSemanticsNode().boundsInRoot
        val narrowSecond = nodes[1].fetchSemanticsNode().boundsInRoot
        val post = rule.onNodeWithTag("timeline_status").fetchSemanticsNode().boundsInRoot
        assertTrue(narrowFirst.height < standardBounds.height)
        assertEquals(narrowFirst.height, narrowSecond.height, 1f)
        assertTrue(narrowSecond.right <= post.right)
        repeat(2) { index ->
            waitForRatio(nodes[index], 180f / 420)
            assertEndsVisible(nodes[index])
        }
    }

    @Test
    fun mixedAttachmentsKeepTheirRatiosAndOriginalViewerIndex() {
        val media = listOf(attachment(image(400, 200)), attachment(image(80, 400)), attachment(image(200, 200)))
        val opened = AtomicReference<Pair<List<MediaAttachment>, Int>>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    StatusCard(status(media), onStatusClick = null, onUnavailableAction = {},
                        onMediaClick = { items, index -> opened.set(items to index) })
                }
            }
        }
        val nodes = rule.onAllNodesWithTag("media_attachment")
        listOf(2f, 0.2f, 1f).forEachIndexed { index, ratio ->
            waitForRatio(nodes[index], ratio)
            assertEndsVisible(nodes[index])
        }
        val firstBounds = nodes[0].fetchSemanticsNode().boundsInRoot
        val secondBounds = nodes[1].fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.height, secondBounds.height, 1f)
        nodes[2].performClick()
        assertEquals(media, opened.get().first)
        assertEquals(2, opened.get().second)
    }

    @Test
    fun urlThumbnailUsesDecodedDimensionsAndStillOpensTheUrl() {
        val portrait = image(120, 600)
        val landscape = image(600, 120)
        val card = mutableStateOf(PreviewCard("https://example.com", "Preview", "Summary", "link", "",
            portrait, aspectRatio = null))
        val opened = AtomicReference<String>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    StatusCard(status().copy(previewCard = card.value), onStatusClick = null,
                        onUnavailableAction = {}, onOpenLink = opened::set)
                }
            }
        }
        val node = rule.onNodeWithTag("preview_card_image", useUnmergedTree = true)
        waitForRatio(node, 0.2f)
        assertEndsVisible(node)
        rule.runOnIdle { card.value = card.value.copy(imageUrl = landscape, aspectRatio = 0.2f) }
        waitForRatio(node, 5f)
        assertEndsVisible(node)
        // The image remains part of the clickable URL card.
        node.performClick()
        assertEquals(card.value.url, opened.get())
    }

    private fun waitForRatio(node: SemanticsNodeInteraction, expected: Float) {
        try {
            rule.waitUntil(10_000) {
                val bounds = node.fetchSemanticsNode().boundsInRoot
                // Downsampled bitmap dimensions and layout pixels both round to integers.
                val tolerance = maxOf(2f, expected, expected * bounds.height * 0.02f)
                kotlin.math.abs(bounds.width - expected * bounds.height) <= tolerance
            }
        } catch (failure: Throwable) {
            throw AssertionError("Expected ratio $expected; actual bounds ${node.fetchSemanticsNode().boundsInRoot}", failure)
        }
    }

    private fun assertEndsVisible(node: SemanticsNodeInteraction) {
        rule.waitUntil(10_000) {
            val pixels = node.captureToImage().toPixelMap()
            val top = pixels[pixels.width / 2, pixels.height / 10]
            val bottom = pixels[pixels.width / 2, pixels.height * 9 / 10]
            top.red > 0.9f && top.green < 0.1f && bottom.green > 0.9f && bottom.red < 0.1f
        }
    }

    private fun image(width: Int, height: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("thumbnail-", ".png", context.cacheDir).also(files::add)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLUE)
        val paint = Paint().apply { color = android.graphics.Color.RED }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.25f, paint)
        paint.color = android.graphics.Color.GREEN
        canvas.drawRect(0f, height * 0.75f, width.toFloat(), height.toFloat(), paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }

    private fun attachment(url: String) = MediaAttachment(url, "image", url, url, "thumbnail")

    private fun status(media: List<MediaAttachment> = emptyList()) = TimelineStatus(
        timelineId = "thumbnail", statusId = "thumbnail", createdAt = "2026-10-02T00:00:00Z",
        author = StatusAuthor("author", "Example", "example", ""), boostedBy = null,
        contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = media,
    )
}
