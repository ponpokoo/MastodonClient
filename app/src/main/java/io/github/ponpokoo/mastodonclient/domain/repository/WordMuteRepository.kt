package io.github.ponpokoo.mastodonclient.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface WordMuteRepository {
    val words: StateFlow<Map<String, List<String>>>
    suspend fun add(sessionId: String, word: String)
    suspend fun remove(sessionId: String, word: String)
    suspend fun restore(sessionId: String, word: String, index: Int)
}
