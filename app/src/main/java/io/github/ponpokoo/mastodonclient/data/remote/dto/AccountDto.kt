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
)
