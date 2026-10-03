package io.github.ponpokoo.mastodonclient

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.DraftMediaDataSource
import io.github.ponpokoo.mastodonclient.domain.model.MediaRejectionReason
import io.github.ponpokoo.mastodonclient.feature.compose.IncomingShareBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private const val AUTHORITY = "io.github.ponpokoo.mastodonclient.test.sharedmedia"
private fun mediaUri(name: String) = Uri.parse("content://$AUTHORITY/$name")

class SharedMediaReceptionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun declaresShareTargetsAndReceivesJpegMp4AndMp3() = runBlocking {
        listOf("image/jpeg" to "jpeg", "video/mp4" to "mp4", "audio/mpeg" to "mp3").forEach { (mime, name) ->
            val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, mediaUri(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertTrue(context.packageManager.queryIntentActivities(intent, 0).any {
                it.activityInfo.packageName == context.packageName
            })
            IncomingShareBus.accept(intent)
            val share = checkNotNull(IncomingShareBus.share.value)
            try {
                val result = DraftMediaDataSource(context).importMedia(share.mediaUris)
                assertEquals(mime, result.attachments.single().mimeType)
                assertTrue(result.rejected.isEmpty())
                assertEquals(4L, File(Uri.parse(result.attachments.single().uri).path!!).length())
                result.attachments.forEach { File(Uri.parse(it.uri).path!!).delete() }
            } finally { IncomingShareBus.consume(share.requestId) }
        }
    }

    @Test fun multipleSharesMergeStreamsAndClipDataAndResolveEachMime() = runBlocking {
        val first = mediaUri("jpeg")
        val second = mediaUri("mp3")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        intent.clipData = ClipData.newUri(context.contentResolver, "media", first).apply {
            addItem(ClipData.Item(mediaUri("jpeg-two")))
        }
        assertTrue(context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg"), 0,
        ).any { it.activityInfo.packageName == context.packageName })
        IncomingShareBus.accept(intent)
        val share = checkNotNull(IncomingShareBus.share.value)
        try {
            assertEquals(listOf(first.toString(), second.toString(), mediaUri("jpeg-two").toString()), share.mediaUris)
            val result = DraftMediaDataSource(context).importMedia(share.mediaUris)
            assertEquals(listOf("image/jpeg", "audio/mpeg", "image/jpeg"), result.attachments.map { it.mimeType })
            result.attachments.forEach { File(Uri.parse(it.uri).path!!).delete() }
        } finally { IncomingShareBus.consume(share.requestId) }
    }

    @Test fun fallsBackOnlyForUnknownOrBroadMimeAndRetainsSuccessesOnReadFailures() = runBlocking {
        val result = DraftMediaDataSource(context).importMedia(listOf(
            "fallback", "broad", "wrong-extension", "no-metadata", "unknown", "broken", "denied",
        ).map { mediaUri(it).toString() })
        try {
            assertEquals(listOf("audio/mpeg", "audio/mpeg", "application/pdf", "image/jpeg"), result.attachments.map { it.mimeType })
            assertEquals(listOf(MediaRejectionReason.UnknownType, MediaRejectionReason.Unreadable, MediaRejectionReason.PermissionDenied),
                result.rejected.map { it.reason })
        } finally { result.attachments.forEach { File(Uri.parse(it.uri).path!!).delete() } }
    }
}
