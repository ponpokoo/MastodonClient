package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.local.ProfileImageDataSource
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageRepository

class DefaultProfileImageRepository(private val source: ProfileImageDataSource) : ProfileImageRepository {
    override suspend fun importImage(uri: String) = source.importImage(uri)
    override suspend fun deleteImages(paths: List<String>) = source.deleteImages(paths)
}
