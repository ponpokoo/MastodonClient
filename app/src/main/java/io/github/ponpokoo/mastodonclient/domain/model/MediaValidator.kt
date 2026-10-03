package io.github.ponpokoo.mastodonclient.domain.model

import java.util.Locale

object MediaValidator {
    fun normalizeMimeType(value: String?): String? = value?.substringBefore(';')?.trim()
        ?.lowercase(Locale.ROOT)?.takeIf {
            it.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) &&
                it != "application/octet-stream"
        }

    fun validate(
        candidates: List<DraftAttachment>,
        existing: List<DraftAttachment>,
        configuration: ComposerConfiguration,
    ): MediaImportResult {
        val accepted = mutableListOf<DraftAttachment>()
        val rejected = mutableListOf<RejectedMedia>()
        val supported = configuration.supportedMimeTypes?.mapNotNull(::normalizeMimeType)?.toSet()
        candidates.distinctBy(DraftAttachment::uri).forEach { item ->
            val mime = normalizeMimeType(item.mimeType)
            val current = existing + accepted
            val reason = when {
                mime == null -> MediaRejectionReason.UnknownType
                supported == null -> MediaRejectionReason.ConfigurationUnavailable
                mime !in supported -> MediaRejectionReason.UnsupportedType
                current.size >= configuration.maxMediaAttachments.coerceAtLeast(0) -> MediaRejectionReason.TooManyAttachments
                current.isNotEmpty() && (isAudioOrVideo(item) || current.any(::isAudioOrVideo)) ->
                    MediaRejectionReason.IncompatibleCombination
                else -> null
            }
            if (reason == null) accepted += item.copy(mimeType = checkNotNull(mime))
            else rejected += RejectedMedia(item.fileName, mime, reason)
        }
        return MediaImportResult(accepted, rejected)
    }

    private fun isAudioOrVideo(item: DraftAttachment): Boolean = when (item.serverType) {
        "audio", "video" -> true
        "image", "gifv" -> false
        else -> normalizeMimeType(item.mimeType)?.let { it.startsWith("audio/") || it.startsWith("video/") } == true
    }
}
