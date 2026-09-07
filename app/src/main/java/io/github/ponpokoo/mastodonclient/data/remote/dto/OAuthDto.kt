package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CredentialApplicationDto(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    @SerialName("redirect_uri") val legacyRedirectUri: String? = null,
)

@Serializable
data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    val scope: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)
