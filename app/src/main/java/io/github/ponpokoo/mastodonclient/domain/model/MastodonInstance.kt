package io.github.ponpokoo.mastodonclient.domain.model

data class MastodonInstance(
    val host: String,
    val baseUrl: String,
    val title: String?,
    val version: String?,
    val maxCharacters: Int,
    val maxMediaAttachments: Int,
)
