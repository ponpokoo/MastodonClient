package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult

interface DraftMediaRepository {
    suspend fun importMedia(uris: List<String>): Result<MediaImportResult>
}
