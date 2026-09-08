package io.github.ponpokoo.mastodonclient.domain.model

data class ComposerConfiguration(
    val maxCharacters: Int = 500,
    val maxMediaAttachments: Int = 4,
    val mediaDescriptionLimit: Int = 1_500,
    val supportedMimeTypes: Set<String> = emptySet(),
)

data class CustomEmoji(
    val shortcode: String,
    val url: String,
    val staticUrl: String,
    val category: String? = null,
)

data class MediaUpload(
    val fileName: String,
    val mimeType: String,
    val filePath: String,
    val description: String?,
)

data class UploadedMedia(
    val id: String,
    val type: String,
    val previewUrl: String?,
    val description: String?,
)

data class CreateStatusRequest(
    val text: String,
    val replyToId: String? = null,
    val mediaIds: List<String> = emptyList(),
    val spoilerText: String = "",
    val sensitive: Boolean = false,
    val visibility: String = "public",
    val language: String? = null,
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long? = null,
    val pollMultiple: Boolean = false,
)
