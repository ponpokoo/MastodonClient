package io.github.ponpokoo.mastodonclient.domain.model

data class DraftAttachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val description: String = "",
    val mediaId: String? = null,
    val uploadedDescription: String? = null,
    val transferState: MediaTransferState = MediaTransferState.Waiting,
    val progress: Float? = null,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val serverType: String? = null,
    val validationError: Boolean = false,
)

enum class MediaTransferState { Waiting, Uploading, Processing, UpdatingAlt, Ready, Failed, CheckAgain }
