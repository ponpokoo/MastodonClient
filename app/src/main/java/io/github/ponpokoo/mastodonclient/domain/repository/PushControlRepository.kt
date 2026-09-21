package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.flow.StateFlow

enum class PushControlStatus { PREPARING, OFF, NEEDS_AUTH, REGISTERING, ACTIVE, REMOVING, ERROR }
data class PushControlState(val enabled: Boolean = false, val status: PushControlStatus = PushControlStatus.PREPARING, val message: String? = null)
interface PushControlRepository {
    val states: StateFlow<Map<String, PushControlState>>
    suspend fun refresh()
    suspend fun setEnabled(sessionId: String, enabled: Boolean)
    suspend fun tokenChanged(token: String?)
}

interface PushAuthLifecycle {
    suspend fun beforeLogout(session: AccountSession)
    suspend fun beforeReauthorization(session: AccountSession)
    suspend fun afterAuthorization(session: AccountSession)
}
