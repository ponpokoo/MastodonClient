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

    @Test fun rejectsUnsupportedOrUnsafeShares() {
        assertNull(parseIncomingShare("android.intent.action.VIEW", "text/plain", "text", emptyList()))
        assertNull(parseIncomingShare(Intent.ACTION_SEND, "image/png", null, listOf("file:///private/image.png")))
    }

    @Test fun acceptsVideoAudioAndBroadTypesForPerUriValidation() {
        listOf("video/mp4", "audio/mpeg", "audio/*", "*/*", null).forEach { type ->
            val share = parseIncomingShare(Intent.ACTION_SEND, type, null, listOf("content://files/media"))
            assertEquals(listOf("content://files/media"), share?.mediaUris)
        }
    }

    @Test fun normalizesMultipleUrisPreservingOrderAndRejectingUnsafeUris() {
        val share = parseIncomingShare(Intent.ACTION_SEND_MULTIPLE, "*/*", "caption", listOf(
            "content://files/first", "content://files/second", "content://files/first", "file:///private/third",
        ))
        assertEquals(listOf("content://files/first", "content://files/second"), share?.mediaUris)
        assertEquals("caption", share?.text)
    }
}
