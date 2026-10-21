package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.WeakHashMap

private val dimensionCaches = WeakHashMap<ImageLoader, EmojiDimensionsCache>()

internal class EmojiImages(val painters: Map<String, AsyncImagePainter>, val ratios: Map<String, Float>)

/** Lifecycle-bound painters, plus dimensions whose lifetime follows the retained post data. */
@Composable
internal fun rememberEmojiImages(urls: List<String>, owner: Any): EmojiImages {
    val context = LocalContext.current
    val loader = SingletonImageLoader.get(context)
    val cache = remember(loader) { dimensionCaches.getOrPut(loader) { EmojiDimensionsCache() } }
    val ownerKey = OwnerKey(owner)
    val painterList = urls.map { url ->
        key(url) {
            val request = remember(context, url) {
                ImageRequest.Builder(context).data(url).size(1024, 128)
                    .animatedEmoji().build()
            }
            rememberAsyncImagePainter(request, imageLoader = loader, contentScale = ContentScale.Fit)
        }
    }
    val painters = remember(urls, painterList) { urls.zip(painterList).toMap() }
    var ratios by remember(cache, ownerKey, urls) {
        mutableStateOf(cache.get(owner, urls).let { saved -> urls.associateWith { saved[it] ?: 1f } })
    }
    LaunchedEffect(cache, ownerKey, painters) {
        if (painters.isEmpty()) return@LaunchedEffect
        // Observe requests outside composition. Loading/Empty changes do not rebuild the text/row.
        val completed = combine(painterList.map { it.state }) { it.toList() }.first { states ->
            states.all { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
        }
        val resolved = cache.get(owner, urls) + urls.zip(completed).mapNotNull { (url, state) ->
            val image = (state as? AsyncImagePainter.State.Success)?.result?.image
            image?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { url to it.width.toFloat() / it.height }
        }.toMap()
        cache.put(owner, resolved)
        // One dimension update, even when completion arrives in several batches or some URLs fail.
        ratios = urls.associateWith { resolved[it] ?: ratios.getValue(it) }
    }
    return remember(painters, ratios) { EmojiImages(painters, ratios) }
}

private class OwnerKey(val owner: Any) {
    override fun hashCode(): Int = System.identityHashCode(owner)
    override fun equals(other: Any?): Boolean = other is OwnerKey && owner === other.owner
}
