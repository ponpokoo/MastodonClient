package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.remote.dto.TagDto
import io.github.ponpokoo.mastodonclient.domain.model.SearchTag

internal fun TagDto.toSearchTag(): SearchTag {
    val latest = history?.filter { it.day.toLongOrNull() != null }?.maxByOrNull { it.day.toLong() }
    return SearchTag(name, url, latest?.accounts?.toLongOrNull()?.takeIf { it >= 0 }, following)
}
