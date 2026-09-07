package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance

interface InstanceRepository {
    suspend fun discover(input: String): Result<MastodonInstance>
}
