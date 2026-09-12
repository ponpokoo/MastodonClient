package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment

interface DraftMediaRepository {
    suspend fun importMedia(uris: List<String>): Result<List<DraftAttachment>>
}
