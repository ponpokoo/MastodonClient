package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Filter before delivering UI state, and discard hidden rows so unmute does not restore stale data. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal fun <T> MutableStateFlow<T>.moderated(
    scope: CoroutineScope,
    repository: TimelineRepository,
    session: () -> AccountSession?,
    filter: (T, AccountModerationState, AccountSession) -> T,
): StateFlow<T> {
    val source = this
    fun visible(value: T, moderation: AccountModerationState): T =
        session()?.let { filter(value, moderation, it) } ?: value
    fun presentation() = repository.moderation.value.copy(wordMutes = repository.wordMutes.value)
    val derived = combine(source, repository.moderation, repository.wordMutes) { value, moderation, words ->
        visible(value, moderation.copy(wordMutes = words))
    }
        .onEach { source.update { visible(it, repository.moderation.value) } }
        .stateIn(scope, SharingStarted.Eagerly, visible(source.value, presentation()))
    return object : StateFlow<T> by derived {
        override val value: T get() = visible(source.value, presentation())
        override val replayCache: List<T> get() = listOf(value)
    }
}
