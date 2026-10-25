package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.core.image.AvatarImages
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public account icons share URL keys across UI and Android notifications. */
@Composable
fun accountAvatarModel(url: String?): Any? {
    if (url.isNullOrBlank() || url.toHttpUrlOrNull() == null) return url
    val context = LocalContext.current
    val images = remember(context) { AvatarImages.get(context) }
    val revisions = remember(images, url) {
        images.coordinator.revisions.map { it[url] ?: 0L }.distinctUntilChanged()
    }
    val revision by revisions.collectAsStateWithLifecycle(initialValue = images.coordinator.revisions.value[url] ?: 0L)
    return remember(images, url, revision) { images.request(url, revision) }
}

@Composable
fun accountAvatarModel(session: AccountSession?): Any? =
    accountAvatarModel(session?.avatarUrl)

/** Headers keep their existing independent refresh generation; icons always use accountAvatarModel. */
@Composable
fun profileHeaderModel(url: String?, revision: Long): Any? {
    val context = LocalContext.current
    return remember(context, url, revision) {
        if (url.isNullOrBlank() || revision == 0L) url else {
            val key = "account-avatar:$url:$revision"
            ImageRequest.Builder(context).data(url)
                .memoryCacheKey(key).diskCacheKey(key)
                .httpHeaders(NetworkHeaders.Builder().set("Cache-Control", "no-cache").build())
                .build()
        }
    }
}
