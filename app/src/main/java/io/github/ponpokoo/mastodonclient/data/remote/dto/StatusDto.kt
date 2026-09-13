package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StatusDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val account: AccountDto,
    val content: String = "",
    @SerialName("spoiler_text") val spoilerText: String = "",
    val sensitive: Boolean = false,
    val visibility: String = "public",
    val url: String? = null,
    @SerialName("replies_count") val repliesCount: Long = 0,
    @SerialName("reblogs_count") val reblogsCount: Long = 0,
    @SerialName("favourites_count") val favouritesCount: Long = 0,
    val application: StatusApplicationDto? = null,
    val favourited: Boolean = false,
    val reblogged: Boolean = false,
    val bookmarked: Boolean = false,
    val pinned: Boolean? = null,
    @SerialName("emoji_reactions") val emojiReactions: List<EmojiReactionDto>? = null,
    val emojis: List<AccountEmojiDto> = emptyList(),
    val card: PreviewCardDto? = null,
    @SerialName("media_attachments") val mediaAttachments: List<MediaAttachmentDto> = emptyList(),
    val reblog: StatusDto? = null,
)

@Serializable
data class StatusApplicationDto(
    val name: String,
    val website: String? = null,
)

@Serializable
data class EmojiReactionDto(
    val name: String,
    val count: Long = 0,
    val me: Boolean = false,
    val url: String? = null,
    @SerialName("static_url") val staticUrl: String? = null,
    @SerialName("account_ids") val accountIds: List<String> = emptyList(),
)

@Serializable
data class StatusContextDto(
    val ancestors: List<StatusDto> = emptyList(),
    val descendants: List<StatusDto> = emptyList(),
)

@Serializable
data class PreviewCardDto(
    val url: String,
    val title: String = "",
    val description: String = "",
    val type: String = "link",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("provider_name") val providerName: String = "",
    val image: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class MediaAttachmentDto(
    val id: String,
    val type: String,
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class CustomEmojiDto(
    val shortcode: String,
    val url: String,
    @SerialName("static_url") val staticUrl: String,
    val category: String? = null,
)

@Serializable
data class StatusSourceDto(
    val id: String,
    val text: String = "",
    @SerialName("spoiler_text") val spoilerText: String = "",
)
