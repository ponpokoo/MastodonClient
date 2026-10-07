package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification

fun interface PushSyncSource {
    suspend fun page(session: AccountSession, sinceId: String?, maxId: String?): List<TimelineNotification>
}

class MastodonPushSyncDataSource(private val clients: ApiClientFactory) {
    suspend fun page(session: AccountSession, sinceId: String?, maxId: String?) =
        clients.create(session.instanceUrl, session.accessToken)
            .getNotifications(maxId = maxId, sinceId = sinceId, limit = 40)

    suspend fun baseline(session: AccountSession): String? =
        clients.create(session.instanceUrl, session.accessToken).getNotifications(limit = 1).firstOrNull()?.id
}
