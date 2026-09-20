package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification

fun interface SystemNotificationRepository {
    suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: () -> Boolean)
}
