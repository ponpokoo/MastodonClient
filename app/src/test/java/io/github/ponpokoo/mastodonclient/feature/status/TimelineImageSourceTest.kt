package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TimelineImageSourceTest {
    private val image = MediaAttachment("image", "image", "original", "preview", null,
        aspectRatio = 2f, previewWidth = 600, previewHeight = 300)

    @Test fun selectsPreviewOnlyWhenTheRenderedPixelsDoNotRequireUpscaling() {
        assertEquals("preview", image.timelineImageUrl(600f, 300f, cropped = true))
        assertEquals("original", image.timelineImageUrl(601f, 300f, cropped = true))
        // The same 300 x 150 dp frame on a 3x-density screen needs the original.
        assertEquals("preview", image.timelineImageUrl(300f, 150f, cropped = true))
        assertEquals("original", image.timelineImageUrl(900f, 450f, cropped = true))
    }

    @Test fun fitUsesTheDrawnImageSizeWhileCropMustFillBothFrameDimensions() {
        assertEquals("preview", image.timelineImageUrl(800f, 200f, cropped = false))
        assertEquals("original", image.timelineImageUrl(800f, 200f, cropped = true))
        assertEquals("preview", image.copy(previewWidth = 461, previewHeight = 346, aspectRatio = 4f / 3f)
            .timelineImageUrl(400f, 300f, cropped = true))
    }

    @Test fun unknownInvalidAndDifferentlyCroppedMetadataUseTheOriginal() {
        for (item in listOf(image.copy(previewWidth = null), image.copy(previewHeight = null),
            image.copy(previewWidth = 0), image.copy(previewHeight = -1), image.copy(aspectRatio = null),
            image.copy(aspectRatio = Float.NaN), image.copy(previewHeight = 600), image.copy(previewUrl = ""))) {
            assertEquals("original", item.timelineImageUrl(100f, 100f, cropped = false))
        }
        assertEquals("original", image.timelineImageUrl(Float.NaN, 100f, cropped = false))
        assertEquals("original", image.timelineImageUrl(100f, 0f, cropped = false))
    }

    @Test fun absentUrlsAndVideoThumbnailsKeepTheirExistingFallbacks() {
        assertEquals("preview", image.copy(url = null).timelineImageUrl(2000f, 2000f, cropped = true))
        assertEquals("original", image.copy(previewUrl = null).timelineImageUrl(100f, 100f, cropped = true))
        assertNull(image.copy(url = null, previewUrl = null).timelineImageUrl(100f, 100f, cropped = true))
        for (type in listOf("video", "gifv")) {
            assertEquals("preview", image.copy(type = type).timelineImageUrl(2000f, 2000f, cropped = true))
        }
    }

    @Test fun serializedAttachmentsKeepDimensionsAndOldCacheEntriesStillLoad() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(image, json.decodeFromString<MediaAttachment>(json.encodeToString(image)))
        val old = json.decodeFromString<MediaAttachment>(
            """{"id":"old","type":"image","url":"original","previewUrl":"preview","description":null}""")
        assertNull(old.previewWidth)
        assertEquals("original", old.timelineImageUrl(100f, 100f, cropped = false))
    }
}
