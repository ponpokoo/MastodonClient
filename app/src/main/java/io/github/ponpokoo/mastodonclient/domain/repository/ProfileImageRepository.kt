package io.github.ponpokoo.mastodonclient.domain.repository

data class ImportedProfileImage(val path: String, val isGif: Boolean)

/** Edges relative to the oriented source image, from 0 to 1. */
data class ProfileImageCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Temporary images owned by the profile editor, separate from persistent draft media. */
interface ProfileImageRepository {
    suspend fun importImage(uri: String): ImportedProfileImage
    suspend fun cropImage(path: String, crop: ProfileImageCrop, maxWidth: Int, aspectRatio: Int): String
    suspend fun deleteImages(paths: List<String>)
}
