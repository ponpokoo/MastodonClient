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
            streamUri = null,
        )

        assertEquals("https://example.com/article", share?.text)
        assertNull(share?.imageUri)
    }

    @Test fun acceptsContentImageAndOptionalCaption() {
        val share = parseIncomingShare(
            action = Intent.ACTION_SEND,
            mimeType = "image/png",
            text = "caption",
            streamUri = "content://gallery/image/42",
        )

        assertNotNull(share)
        assertEquals("caption", share?.text)
        assertEquals("content://gallery/image/42", share?.imageUri)
    }

    @Test fun rejectsUnsupportedOrUnsafeShares() {
        assertNull(parseIncomingShare("android.intent.action.VIEW", "text/plain", "text", null))
        assertNull(parseIncomingShare(Intent.ACTION_SEND, "application/pdf", "caption", "content://files/42"))
        assertNull(parseIncomingShare(Intent.ACTION_SEND, "image/png", null, "file:///private/image.png"))
    }
}
