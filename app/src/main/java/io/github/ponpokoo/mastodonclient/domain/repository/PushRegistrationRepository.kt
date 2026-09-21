package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

enum class PushRegistrationState { DISABLED, REGISTERING, ACTIVE, REMOVING }

interface PushRegistrationRepository {
    suspend fun state(session: AccountSession): PushRegistrationState
    /** Explicit opt-in, or retry of the same opt-in. Must have push OAuth scope. */
    suspend fun enable(session: AccountSession, fcmToken: String, alerts: Map<String, Boolean>, standard: Boolean? = null)
    /** Call before discarding the session credentials; retry on failure. */
    suspend fun disable(session: AccountSession)
}
