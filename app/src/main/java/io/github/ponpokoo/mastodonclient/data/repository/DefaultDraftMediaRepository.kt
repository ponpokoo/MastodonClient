package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.data.local.DraftMediaDataSource
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository

class DefaultDraftMediaRepository(private val source: DraftMediaDataSource) : DraftMediaRepository {
    override suspend fun importMedia(sessionId: String, uris: List<String>) = runCatchingCancellable { source.importMedia(sessionId, uris) }
}
