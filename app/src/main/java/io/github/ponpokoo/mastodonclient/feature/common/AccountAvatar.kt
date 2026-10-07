package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

@Composable
fun accountAvatarModel(session: AccountSession?): Any? =
    accountAvatarModel(session?.avatarUrl, session?.avatarRevision ?: 0)

/** Re-fetch this account image after a successful synchronization/refresh, even for an unchanged URL. */
@Composable
fun accountAvatarModel(url: String?, revision: Long): Any? {
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
