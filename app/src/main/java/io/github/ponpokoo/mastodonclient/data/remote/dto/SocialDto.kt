package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    @SerialName("created_at") val createdAt: String,
    val account: AccountDto,
    val status: StatusDto? = null,
)

@Serializable
data class SearchResultDto(
    val accounts: List<AccountDto> = emptyList(),
    val statuses: List<StatusDto> = emptyList(),
    val hashtags: List<TagDto> = emptyList(),
)

@Serializable
data class TagDto(
    val name: String,
    val url: String,
)

@Serializable
data class MarkerResponseDto(
    val notifications: MarkerDto? = null,
)

@Serializable
data class MarkerDto(
    @SerialName("last_read_id") val lastReadId: String,
    val version: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ListDto(
    val id: String,
    val title: String,
)
