package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.PushNotification

enum class PushReceiveResult { PROCESSED, IGNORED, REJECTED }
fun interface PushMessageRepository {
    /** Transport data only; no Firebase SDK dependency. IO failures propagate for caller retry. */
    suspend fun receive(data: Map<String, String>): PushReceiveResult
}
fun interface PushNotificationPresenter {
    suspend fun show(session: AccountSession, notification: PushNotification, isCurrent: suspend () -> Boolean)
}
