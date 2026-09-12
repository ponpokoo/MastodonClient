package io.github.ponpokoo.mastodonclient.domain.model

data class DraftAttachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val description: String = "",
)
