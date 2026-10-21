package io.github.ponpokoo.mastodonclient.domain.repository

import kotlinx.coroutines.flow.Flow

fun interface ServerSuggestionsRepository {
    fun observeDomains(): Flow<List<String>>
}
