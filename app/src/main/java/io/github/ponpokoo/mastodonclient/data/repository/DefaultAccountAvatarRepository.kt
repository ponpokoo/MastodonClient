package io.github.ponpokoo.mastodonclient.data.repository

import android.content.Context
import io.github.ponpokoo.mastodonclient.core.image.AvatarImages
import io.github.ponpokoo.mastodonclient.domain.repository.AccountAvatarRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DefaultAccountAvatarRepository(context: Context) : AccountAvatarRepository {
    private val images = AvatarImages.get(context)
    override suspend fun revalidate(url: String): Boolean =
        url.toHttpUrlOrNull() != null && images.revalidate(url)
}
