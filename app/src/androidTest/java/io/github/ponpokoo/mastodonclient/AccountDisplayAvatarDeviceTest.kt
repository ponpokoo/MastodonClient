package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.ponpokoo.mastodonclient.core.image.AvatarImage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class AccountDisplayAvatarDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun ownMetadataChangesKeepTheUrlRequestAndViewerUsesTheSharedAvatarModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = SingletonImageLoader.get(context)
        val requests = AtomicInteger()
        val loader = ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                val data = chain.request.data
                assertTrue("Self icons and viewer must use the common avatar pipeline", data is AvatarImage)
                assertEquals("https://images.example/self.png", (data as AvatarImage).url)
                requests.incrementAndGet()
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.RED)
                }
                SuccessResult(bitmap.asImage(), chain.request)
            })
        }.build()
        val account = mutableStateOf(AccountSession("one", "https://one.example", "me", "me", "Me",
            "https://images.example/self.png", "test-token"))
        val viewer = mutableStateOf(false)
        try {
            SingletonImageLoader.setUnsafe(loader)
            rule.setContent {
                if (viewer.value) MediaViewerScreen(
                    media = listOf(MediaAttachment("avatar", "image", account.value.avatarUrl,
                        account.value.avatarUrl, "プロフィール画像", isAccountAvatar = true)),
                    initialIndex = 0, onBack = {},
                ) else AsyncImage(accountAvatarModel(account.value), null,
                    modifier = Modifier.size(48.dp).testTag("registered_avatar"))
            }
            waitFor("registered_avatar")
            val initialRequests = requests.get()
            rule.runOnIdle { account.value = account.value.copy(displayName = "Renamed", username = "renamed") }
            rule.waitForIdle()
            assertEquals(initialRequests, requests.get())
            rule.runOnIdle { viewer.value = true }
            waitFor("media_viewer")
            assertTrue(requests.get() > initialRequests)
        } finally {
            SingletonImageLoader.setUnsafe(previous)
            loader.shutdown()
        }
    }

    private fun waitFor(tag: String) {
        rule.waitUntil(10_000) {
            val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2] == Color.Red
        }
    }
}
