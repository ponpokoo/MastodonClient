package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstanceDto(
    val domain: String? = null,
    val title: String? = null,
    val version: String? = null,
    val configuration: InstanceConfigurationDto? = null,
    val urls: InstanceUrlsDto? = null,
    @SerialName("fedibird_capabilities") val fedibirdCapabilities: List<String>? = null,
)

@Serializable
data class InstanceUrlsDto(
    val streaming: String? = null,
)

@Serializable
data class InstanceConfigurationDto(
    val statuses: StatusConfigurationDto? = null,
    @SerialName("media_attachments") val mediaAttachments: MediaConfigurationDto? = null,
    val urls: InstanceUrlsDto? = null,
)

@Serializable
data class StatusConfigurationDto(
    @SerialName("max_characters") val maxCharacters: Int? = null,
    @SerialName("max_media_attachments") val maxMediaAttachments: Int? = null,
)

@Serializable
data class MediaConfigurationDto(
    @SerialName("max_attachments") val maxAttachments: Int? = null,
    @SerialName("description_limit") val descriptionLimit: Int? = null,
    @SerialName("supported_mime_types") val supportedMimeTypes: List<String> = emptyList(),
)
