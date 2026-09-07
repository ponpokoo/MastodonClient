package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.network.InstanceUrlNormalizer
import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance
import io.github.ponpokoo.mastodonclient.domain.repository.InstanceRepository
import java.net.URI

class DefaultInstanceRepository(
    private val apiClientFactory: ApiClientFactory,
) : InstanceRepository {
    override suspend fun discover(input: String): Result<MastodonInstance> = runCatching {
        val baseUrl = InstanceUrlNormalizer.normalize(input).getOrThrow()
        val dto = apiClientFactory.create(baseUrl).getInstance()
        MastodonInstance(
            host = dto.domain ?: checkNotNull(URI(baseUrl).host),
            baseUrl = baseUrl,
            title = dto.title,
            version = dto.version,
            maxCharacters = dto.configuration?.statuses?.maxCharacters ?: 500,
            maxMediaAttachments = dto.configuration?.mediaAttachments?.maxAttachments ?: 4,
        )
    }
}
