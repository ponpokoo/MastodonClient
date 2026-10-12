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
    fun filters(settings: Map<String, io.github.ponpokoo.mastodonclient.domain.model.ServerAccountSettings> = repository.serverSettings.value): Map<String, List<io.github.ponpokoo.mastodonclient.domain.model.ServerFilter>> {
        val account = session() ?: return emptyMap()
        return settings[account.sessionId]?.takeIf { it.belongsTo(account) }?.filters?.let { mapOf(account.sessionId to it) } ?: emptyMap()
    }
    fun presentation() = repository.moderation.value.copy(serverFilters = filters(), wordMutes = repository.wordMutes.value)
    val derived = combine(source, repository.moderation, repository.serverSettings, repository.wordMutes) { value, moderation, settings, words ->
        visible(value, moderation.copy(serverFilters = filters(settings), wordMutes = words))
    }
        .onEach { source.update { visible(it, repository.moderation.value.copy(applyServerFilters = false)) } }
        .stateIn(scope, SharingStarted.Eagerly, visible(source.value, presentation()))
    return object : StateFlow<T> by derived {
        override val value: T get() = visible(source.value, presentation())
        override val replayCache: List<T> get() = listOf(value)
    }
}
