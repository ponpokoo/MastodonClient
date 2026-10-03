package io.github.ponpokoo.mastodonclient.feature.compose

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IncomingShare(
    val requestId: String,
    val text: String? = null,
    val mediaUris: List<String> = emptyList(),
)

object IncomingShareBus {
    private val mutableShare = MutableStateFlow<IncomingShare?>(null)
    val share = mutableShare.asStateFlow()

    fun accept(intent: Intent?) {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val clipUris = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        parseIncomingShare(
            action = intent.action,
            mimeType = intent.type,
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            streamUris = streams.map(Uri::toString),
            clipUris = clipUris.map(Uri::toString),
        )?.let { mutableShare.value = it }
    }

    fun consume(requestId: String) {
        if (mutableShare.value?.requestId == requestId) mutableShare.value = null
    }
}

internal fun parseIncomingShare(
    action: String?,
    mimeType: String?,
    text: String?,
    streamUris: List<String>,
    clipUris: List<String> = emptyList(),
): IncomingShare? {
    if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val isTextShare = mimeType?.substringBefore(';')?.trim()?.equals("text/plain", ignoreCase = true) == true
    val normalizedText = text?.trim()?.take(MAX_SHARED_TEXT_LENGTH)?.takeIf(String::isNotEmpty)
    // Text shares can carry a Sharesheet preview in ClipData; only EXTRA_STREAM is an attachment.
    val mediaUris = (streamUris + if (isTextShare) emptyList() else clipUris).filter {
        runCatching { URI(it).scheme == "content" }.getOrDefault(false)
    }.distinct()
    if (mediaUris.isEmpty() && (!isTextShare || normalizedText == null)) return null
    return IncomingShare(
        requestId = UUID.randomUUID().toString(),
        text = normalizedText,
        mediaUris = mediaUris,
    )
}

private const val MAX_SHARED_TEXT_LENGTH = 10_000
