package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

private class DisplayedProfileImage(var painter: Painter? = null)

/** Retains only successful images, within the current session/account/image role. */
@Composable
internal fun ProfileImage(
    model: Any?,
    identity: Any,
    retainPreviousImage: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalContext.current),
) {
    key(identity) {
        val displayed = remember { DisplayedProfileImage() }
        AsyncImage(
            model = model, imageLoader = imageLoader, contentDescription = contentDescription,
            modifier = modifier, contentScale = contentScale,
            transform = { state ->
                if (!retainPreviousImage) state else when (state) {
                    is AsyncImagePainter.State.Success -> state.also { displayed.painter = it.painter }
                    is AsyncImagePainter.State.Loading -> state.copy(painter = displayed.painter ?: state.painter)
                    is AsyncImagePainter.State.Error -> state.copy(painter = displayed.painter ?: state.painter)
                    else -> state
                }
            },
        )
    }
}
