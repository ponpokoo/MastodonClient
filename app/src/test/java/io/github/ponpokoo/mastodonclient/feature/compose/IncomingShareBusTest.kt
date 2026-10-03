package io.github.ponpokoo.mastodonclient.feature.compose

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareBusTest {
    @Test fun acceptsSharedUrlAsComposerText() {
        val share = parseIncomingShare(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            text = "  https://example.com/article  ",
            streamUris = emptyList(),
        )

        assertEquals("https://example.com/article", share?.text)
        assertEquals(emptyList<String>(), share?.mediaUris)
    }

    @Test fun acceptsContentImageAndOptionalCaption() {
        val share = parseIncomingShare(
            action = Intent.ACTION_SEND,
            mimeType = "image/png",
            text = "caption",
            streamUris = listOf("content://gallery/image/42"),
        )

        assertNotNull(share)
        assertEquals("caption", share?.text)
        assertEquals(listOf("content://gallery/image/42"), share?.mediaUris)
    }

    @Test fun excludesClipDataPreviewsFromTextShares() {
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            listOf("text/plain", "TEXT/PLAIN; charset=UTF-8").forEach { mime ->
                val share = parseIncomingShare(action, mime, " https://example.com/article ", emptyList(),
                    clipUris = listOf("content://browser/favicon", "content://browser/thumbnail"))

                assertEquals("https://example.com/article", share?.text)
                assertEquals(emptyList<String>(), share?.mediaUris)
            }
        }
    }

    @Test fun retainsExplicitAttachmentInTextShareWithoutPreview() {
        val share = parseIncomingShare(Intent.ACTION_SEND, "text/plain", "caption",
            streamUris = listOf("content://files/image"), clipUris = listOf("content://browser/favicon"))

        assertEquals("caption", share?.text)
        assertEquals(listOf("content://files/image"), share?.mediaUris)
    }

    @Test fun rejectsTextPreviewWithNoTextOrExplicitAttachment() {
        assertNull(parseIncomingShare(Intent.ACTION_SEND, "text/plain", " ", emptyList(),
            clipUris = listOf("content://browser/favicon")))
    }

    @Test fun rejectsUnsupportedOrUnsafeShares() {
        assertNull(parseIncomingShare("android.intent.action.VIEW", "text/plain", "text", emptyList()))
        assertNull(parseIncomingShare(Intent.ACTION_SEND, "image/png", null, listOf("file:///private/image.png")))
    }

    @Test fun acceptsVideoAudioAndBroadTypesForPerUriValidation() {
        listOf("video/mp4", "audio/mpeg", "audio/*", "*/*", null).forEach { type ->
            val share = parseIncomingShare(Intent.ACTION_SEND, type, null, emptyList(),
                clipUris = listOf("content://files/media"))
            assertEquals(listOf("content://files/media"), share?.mediaUris)
        }
    }

    @Test fun normalizesMultipleUrisPreservingOrderAndRejectingUnsafeUris() {
        val share = parseIncomingShare(Intent.ACTION_SEND_MULTIPLE, "*/*", "caption",
            streamUris = listOf("content://files/first", "content://files/second"),
            clipUris = listOf("content://files/first", "content://files/third", "file:///private/fourth"))
        assertEquals(listOf("content://files/first", "content://files/second", "content://files/third"), share?.mediaUris)
        assertEquals("caption", share?.text)
    }
}
