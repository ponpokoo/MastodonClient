package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountDto(
    val id: String,
    val username: String,
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String = "",
    val url: String = "",
    val header: String = "",
    val note: String = "",
    @SerialName("followers_count") val followersCount: Long = 0,
    @SerialName("following_count") val followingCount: Long = 0,
    @SerialName("statuses_count") val statusesCount: Long = 0,
    val locked: Boolean = false,
    val bot: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val fields: List<AccountFieldDto> = emptyList(),
    val emojis: List<AccountEmojiDto> = emptyList(),
    val source: AccountSourceDto? = null,
)

@Serializable
data class AccountFieldDto(
    val name: String,
    val value: String,
    @SerialName("verified_at") val verifiedAt: String? = null,
)

@Serializable
data class AccountEmojiDto(
    val shortcode: String,
    val url: String,
    @SerialName("static_url") val staticUrl: String = url,
)

@Serializable
data class AccountSourceDto(
    val note: String = "",
    val fields: List<AccountSourceFieldDto> = emptyList(),
)

@Serializable
data class AccountSourceFieldDto(val name: String = "", val value: String = "")

@Serializable
data class RelationshipDto(
    val id: String,
    val following: Boolean = false,
    @SerialName("followed_by") val followedBy: Boolean = false,
    val blocking: Boolean = false,
    @SerialName("blocked_by") val blockedBy: Boolean = false,
    val muting: Boolean = false,
    val requested: Boolean = false,
)

@Serializable
data class ReportDto(val id: String)
