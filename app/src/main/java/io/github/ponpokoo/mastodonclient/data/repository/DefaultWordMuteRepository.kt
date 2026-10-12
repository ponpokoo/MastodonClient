package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.repository.WordMuteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DefaultWordMuteRepository(private val store: UserPreferencesStore, scope: CoroutineScope) : WordMuteRepository {
    override val words = store.preferences.map { it.wordMutes }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    override suspend fun add(sessionId: String, word: String) {
        val value = word.trim()
        require(value.isNotEmpty() && value.length <= 100) { "1〜100文字で入力してください" }
        store.editWordMutes(sessionId) { current ->
            require(current.none { it.equals(value, ignoreCase = true) }) { "このワードは登録済みです" }
            current + value
        }
    }
    override suspend fun remove(sessionId: String, word: String) = store.editWordMutes(sessionId) { it - word }
    override suspend fun restore(sessionId: String, word: String, index: Int) = store.editWordMutes(sessionId) {
        if (it.any { existing -> existing.equals(word, ignoreCase = true) }) it
        else it.toMutableList().apply { add(index.coerceIn(0, size), word) }
    }
}
