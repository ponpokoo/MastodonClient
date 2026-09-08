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
)
