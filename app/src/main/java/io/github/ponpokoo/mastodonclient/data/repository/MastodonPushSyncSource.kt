package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.MastodonPushSyncDataSource
import io.github.ponpokoo.mastodonclient.data.remote.PushSyncSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

/** Keep DTO mapping on the repository side of the remote source boundary. */
class MastodonPushSyncSource(clients: ApiClientFactory) : PushSyncSource {
    private val remote = MastodonPushSyncDataSource(clients)
    override suspend fun page(session: AccountSession, sinceId: String?, maxId: String?) =
        remote.page(session, sinceId, maxId).map { it.toDomain() }

    suspend fun baseline(session: AccountSession) = remote.baseline(session)
}
