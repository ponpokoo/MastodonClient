package io.github.ponpokoo.mastodonclient.data.remote.dto

import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class FilterDto(val id: String, val title: String = "", val context: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null, @SerialName("filter_action") val action: String? = null,
    val keywords: List<FilterKeywordDto>? = null, val statuses: List<FilterStatusDto>? = null)
@Serializable data class FilterKeywordDto(val id: String? = null, val keyword: String = "", @SerialName("whole_word") val wholeWord: Boolean = false)
@Serializable data class FilterStatusDto(val id: String, @SerialName("status_id") val statusId: String)
@Serializable data class FilterResultDto(val filter: FilterDto,
    @SerialName("keyword_matches") val keywordMatches: List<String>? = null,
    @SerialName("status_matches") val statusMatches: List<String>? = null)
fun FilterDto.toDomain() = ServerFilter(id, title, context.toSet(), expiresAt, action ?: "warn",
    keywords.orEmpty().map { FilterKeyword(it.id, it.keyword, it.wholeWord) }, statuses.orEmpty().map { FilterStatus(it.id, it.statusId) })
fun FilterResultDto.toDomain() = StatusFilterResult(filter.toDomain(), keywordMatches, statusMatches)
