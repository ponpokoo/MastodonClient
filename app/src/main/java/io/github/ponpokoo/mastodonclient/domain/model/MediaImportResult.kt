package io.github.ponpokoo.mastodonclient.domain.model

data class MediaImportResult(
    val attachments: List<DraftAttachment> = emptyList(),
    val rejected: List<RejectedMedia> = emptyList(),
)

data class RejectedMedia(
    val fileName: String,
    val mimeType: String?,
    val reason: MediaRejectionReason,
)

enum class MediaRejectionReason {
    UnknownType, UnsupportedType, Unreadable, PermissionDenied,
    TooManyAttachments, IncompatibleCombination, ConfigurationUnavailable,
}
