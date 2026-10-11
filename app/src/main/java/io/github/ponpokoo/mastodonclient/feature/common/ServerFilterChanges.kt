package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Refresh already open secondary screens after rules change; opening them never fetches settings. */
internal fun observeServerFilterChanges(scope: CoroutineScope, repository: TimelineRepository,
    session: () -> AccountSession?, screen: String, refresh: suspend () -> Boolean) = scope.launch {
    var previous = repository.serverSettings.value
    repository.serverSettings.collect { settings ->
        val account = session()
        val changed = account != null && settings[account.sessionId]?.belongsTo(account) == true &&
            previous[account.sessionId]?.filters != settings[account.sessionId]?.filters
        previous = settings
        if (changed) trackServerFilterDisplay(repository, account!!, screen, refresh)
    }
}

internal suspend fun trackServerFilterDisplay(repository: TimelineRepository, session: AccountSession,
    screen: String, refresh: suspend () -> Boolean) {
    val filters = repository.serverSettings.value[session.sessionId]?.takeIf { it.belongsTo(session) }?.filters ?: return
    repository.updateFilterDisplay(session, screen, filters, null)
    val success = refresh()
    currentCoroutineContext().ensureActive()
    repository.updateFilterDisplay(session, screen, filters, success)
}
