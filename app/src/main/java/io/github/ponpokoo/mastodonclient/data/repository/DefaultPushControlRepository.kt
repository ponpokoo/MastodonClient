package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultPushControlRepository(
    private val sessions: suspend () -> List<AccountSession>,
    private val control: PushControlStore,
    private val registrations: PushRegistrationStore,
    private val configured: () -> Boolean,
    // Use the saved relay for cleanup, even if the current configured relay has changed.
    private val repository: (StoredPushRegistration?) -> PushRegistrationRepository,
) : PushControlRepository, PushAuthLifecycle {
    private val mutable = MutableStateFlow<Map<String, PushControlState>>(emptyMap())
    override val states = mutable.asStateFlow()
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock { reconcile() }
    override suspend fun tokenChanged(token: String?) = mutex.withLock {
        require(token == null || token.isNotBlank())
        val data = control.read()
        control.write(PushControlData(data.intents, token))
        mutable.value = emptyMap()
        reconcile()
    }
    override suspend fun setEnabled(sessionId: String, enabled: Boolean) = mutex.withLock {
        val session = sessions().firstOrNull { it.sessionId == sessionId } ?: return@withLock
        val data = control.read()
        if (enabled && !configured()) { update(sessionId, false, PushControlStatus.PREPARING); return@withLock }
        val old = data.intents[sessionId]
        check(!enabled || old?.cleanup == null) { "解除待ちの処理を先に完了してください" }
        control.write(PushControlData(data.intents + (sessionId to PushIntent(enabled, awaitingAuthorization = enabled && old?.awaitingAuthorization == true)), data.token))
        reconcileOne(session)
    }
    override suspend fun beforeLogout(session: AccountSession) = mutex.withLock {
        val data = control.read()
        // Persist credentials strictly for remote cleanup before removing them from login sessions.
        control.write(PushControlData(data.intents + (session.sessionId to PushIntent(false, session)), data.token))
        markRemoving(session)
        cleanup(session) // A network failure leaves durable cleanup and does not block local logout.
        Unit
    }
    override suspend fun beforeReauthorization(session: AccountSession) = mutex.withLock {
        val data = control.read()
        control.write(PushControlData(data.intents + (session.sessionId to PushIntent(true, session, awaitingAuthorization = true)), data.token))
        markRemoving(session)
        check(cleanup(session)) { "通知の解除を完了できませんでした。再試行してください" }
    }
    override suspend fun afterAuthorization(session: AccountSession) = mutex.withLock {
        val data = control.read()
        data.intents[session.sessionId]?.let { intent ->
            control.write(PushControlData(data.intents + (session.sessionId to intent.copy(awaitingAuthorization = false)), data.token))
        }
        reconcileOne(session)
    }

    private suspend fun markRemoving(session: AccountSession) = PushRegistrationGuard.mutex.withLock {
        registrations.read(session.sessionId)?.let { registrations.write(session.sessionId, it.copy(state = PushRegistrationState.REMOVING)) }
    }
    private suspend fun cleanup(session: AccountSession): Boolean {
        update(session.sessionId, false, PushControlStatus.REMOVING)
        return runCatchingCancellable {
            registrations.read(session.sessionId)?.let { repository(it).disable(session) }
            val data = control.read()
            val desired = data.intents[session.sessionId]?.enabled == true
            control.write(PushControlData(if (desired) data.intents + (session.sessionId to PushIntent(true, awaitingAuthorization = data.intents[session.sessionId]?.awaitingAuthorization == true)) else data.intents - session.sessionId, data.token))
            update(session.sessionId, desired, if (desired) PushControlStatus.NEEDS_AUTH else PushControlStatus.OFF)
            true
        }.getOrElse {
            update(session.sessionId, false, PushControlStatus.REMOVING, "解除待ちです。起動時または再試行時に確認します。")
            false
        }
    }
    private suspend fun reconcile() {
        val pending = control.read().intents.values.mapNotNull { it.cleanup }
        pending.forEach { cleanup(it) }
        val accounts = sessions()
        mutable.update { states -> states.filterKeys { key -> accounts.any { it.sessionId == key } || controlKeys(pending, key) } }
        accounts.forEach { session ->
            runCatchingCancellable { reconcileOne(session) }.onFailure {
                update(session.sessionId, control.read().intents[session.sessionId]?.enabled == true, PushControlStatus.ERROR, "通知設定を確認できませんでした。再試行してください。")
            }
        }
    }
    private fun controlKeys(pending: List<AccountSession>, key: String) = pending.any { it.sessionId == key }
    private suspend fun reconcileOne(session: AccountSession) {
        val data = control.read()
        val intent = data.intents[session.sessionId] ?: PushIntent()
        if (intent.cleanup != null) return
        val saved = registrations.read(session.sessionId)
        if (!intent.enabled) {
            if (saved != null) {
                control.write(PushControlData(data.intents + (session.sessionId to PushIntent(false, session)), data.token))
                markRemoving(session); cleanup(session)
            } else update(session.sessionId, false, if (configured()) PushControlStatus.OFF else PushControlStatus.PREPARING)
            return
        }
        if (!configured()) { update(session.sessionId, true, PushControlStatus.PREPARING); return }
        if (intent.awaitingAuthorization) { update(session.sessionId, true, PushControlStatus.NEEDS_AUTH); return }
        if ("push" !in session.scopes.split(' ')) { update(session.sessionId, true, PushControlStatus.NEEDS_AUTH); return }
        val token = data.token
        if (token.isNullOrBlank()) { update(session.sessionId, true, PushControlStatus.PREPARING, "通知サービスの準備を待っています。"); return }
        if (saved?.state == PushRegistrationState.ACTIVE && mutable.value[session.sessionId]?.status == PushControlStatus.ACTIVE) return
        update(session.sessionId, true, PushControlStatus.REGISTERING)
        runCatchingCancellable {
            repository(saved).enable(session, token, ALERTS)
            update(session.sessionId, true, PushControlStatus.ACTIVE)
        }.onFailure { update(session.sessionId, true, PushControlStatus.ERROR, "通知を登録できませんでした。再試行してください。") }
    }
    private fun update(id: String, enabled: Boolean, status: PushControlStatus, message: String? = null) {
        mutable.update { it + (id to PushControlState(enabled, status, message)) }
    }
    private companion object { val ALERTS = mapOf("mention" to true, "favourite" to true, "reblog" to true, "follow" to true, "follow_request" to true, "poll" to true) }
}
