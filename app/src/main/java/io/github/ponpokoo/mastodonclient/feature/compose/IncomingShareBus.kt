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
    val imageUri: String? = null,
)

object IncomingShareBus {
    private val mutableShare = MutableStateFlow<IncomingShare?>(null)
    val share = mutableShare.asStateFlow()

    fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        parseIncomingShare(
            action = intent.action,
            mimeType = intent.type,
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            streamUri = stream?.toString(),
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
    streamUri: String?,
): IncomingShare? {
    if (action != Intent.ACTION_SEND) return null
    val isTextShare = mimeType == "text/plain"
    val isImageShare = mimeType?.startsWith("image/") == true
    if (!isTextShare && !isImageShare) return null
    val normalizedText = text?.trim()?.take(MAX_SHARED_TEXT_LENGTH)?.takeIf(String::isNotEmpty)
    val imageUri = streamUri?.takeIf {
        isImageShare && runCatching { URI(it).scheme == "content" }.getOrDefault(false)
    }
    if (normalizedText == null && imageUri == null) return null
    return IncomingShare(
        requestId = UUID.randomUUID().toString(),
        text = normalizedText,
        imageUri = imageUri,
    )
}

private const val MAX_SHARED_TEXT_LENGTH = 10_000
