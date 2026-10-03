package io.github.ponpokoo.mastodonclient

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.github.ponpokoo.mastodonclient.feature.compose.IncomingShareBus
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlShareReceptionDeviceTest {
    private val preview = Uri.parse("content://browser/share-preview")
    private val attachment = Uri.parse("content://files/shared-media")

    @Test fun urlPreviewIsExcludedAndExplicitAttachmentIsRetained() {
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            listOf(false, true).forEach { hasAttachment ->
                val intent = Intent(action).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.com/article")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("", preview)
                if (hasAttachment) {
                    if (action == Intent.ACTION_SEND_MULTIPLE) {
                        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(attachment))
                    } else {
                        intent.putExtra(Intent.EXTRA_STREAM, attachment)
                    }
                }
                IncomingShareBus.accept(intent)
                val share = checkNotNull(IncomingShareBus.share.value)
                try {
                    assertEquals("https://example.com/article", share.text)
                    assertEquals(if (hasAttachment) listOf(attachment.toString()) else emptyList<String>(), share.mediaUris)
                } finally { IncomingShareBus.consume(share.requestId) }
            }
        }
    }

    @Test fun mediaSharesStillAcceptClipDataAndDeduplicateExplicitStreams() {
        listOf("image/jpeg", "video/mp4", "audio/mpeg", "*/*").forEach { mime ->
            listOf(false, true).forEach { hasStream ->
                val intent = Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_TEXT, "https://example.com/article")
                intent.clipData = ClipData.newRawUri("", attachment)
                if (hasStream) intent.putExtra(Intent.EXTRA_STREAM, attachment)
                IncomingShareBus.accept(intent)
                val share = checkNotNull(IncomingShareBus.share.value)
                try {
                    assertEquals("https://example.com/article", share.text)
                    assertEquals(listOf(attachment.toString()), share.mediaUris)
                } finally { IncomingShareBus.consume(share.requestId) }
            }
        }
    }
}
