package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerCapabilitiesUiState(
    val snapshot: BrowsingSession.Snapshot? = null,
    val capabilities: ServerCapabilities = ServerCapabilities(),
)

/** Projects the shared server cache into the active browsing session. */
class ServerCapabilitiesViewModel(
    private val repository: TimelineRepository,
    browsing: BrowsingSession,
) : SessionScopedViewModel(browsing) {
    private val mutableState = MutableStateFlow(ServerCapabilitiesUiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        refreshJob = null
        mutableState.value = ServerCapabilitiesUiState(snapshot)
        val account = snapshot.account ?: return
        requestScope.launch {
            repository.observeServerCapabilities(account).collect { capabilities ->
                Result.success(capabilities).forSession(snapshot)
                mutableState.value = ServerCapabilitiesUiState(snapshot, capabilities)
            }
        }
        refresh()
    }

    fun refresh() {
        val snapshot = currentSnapshot() ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = requestScope.launch {
            repository.getServerCapabilities(snapshot.account!!).forSession(snapshot).onSuccess { capabilities ->
                mutableState.value = ServerCapabilitiesUiState(snapshot, capabilities)
            }
        }
    }
}
