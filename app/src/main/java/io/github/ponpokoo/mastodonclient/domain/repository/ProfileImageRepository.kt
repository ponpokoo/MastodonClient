package io.github.ponpokoo.mastodonclient.domain.repository

/** Temporary images owned by the profile editor, separate from persistent draft media. */
interface ProfileImageRepository {
    suspend fun importImage(uri: String): String
    suspend fun deleteImages(paths: List<String>)
}
