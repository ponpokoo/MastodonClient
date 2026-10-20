package io.github.ponpokoo.mastodonclient.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class NodeInfoDiscoveryDto(val links: List<NodeInfoLinkDto> = emptyList())

@Serializable
data class NodeInfoLinkDto(val rel: String, val href: String)

@Serializable
data class NodeInfoDto(val metadata: NodeInfoMetadataDto? = null)

@Serializable
data class NodeInfoMetadataDto(val features: List<String>? = null)
