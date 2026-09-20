package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource

class DefaultSystemNotificationRepository(private val local: SystemNotificationDataSource) : SystemNotificationRepository {
    override suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: () -> Boolean) =
        local.showNotification(session, notification, isCurrent)
}
