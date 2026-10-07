package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.EventListener
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import io.github.ponpokoo.mastodonclient.feature.common.ProfileImage
import io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineTopBar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ProfileImageRefreshDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val revision = mutableStateOf(1L)
    private val owner = mutableStateOf("account-a")
    private val address = mutableStateOf("original")
    private val replies = ConcurrentHashMap<String, Reply>()
    private val roles = listOf("avatar", "header", "switcher")
    private lateinit var imageLoader: ImageLoader
    private var previousImageLoader: ImageLoader? = null
    private val errors = AtomicInteger()

    private class Reply(val color: Color?, val nonCancellable: Boolean = false, val corrupt: Boolean = false) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    private fun prepare(number: Long, color: Color?, ready: Boolean = false,
        path: String = "original", nonCancellable: Boolean = false, corrupt: Boolean = false): List<Reply> = roles.map { role ->
        Reply(color, nonCancellable, corrupt).also {
            if (ready) it.release.complete(Unit)
            replies["account-avatar:https://images.example/$path/$role:$number"] = it
        }
    }

    private fun render() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).eventListener(object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) { errors.incrementAndGet() }
        }).components {
            add(Fetcher.Factory<Uri> { _, options, _ ->
                val reply = replies[options.diskCacheKey] ?: error("Unexpected image generation")
                Fetcher {
                    reply.started.complete(Unit)
                    try {
                        if (reply.nonCancellable) withContext(NonCancellable) { reply.release.await() }
                        else reply.release.await()
                        if (reply.corrupt) return@Fetcher SourceFetchResult(
                            ImageSource(Buffer().writeUtf8("invalid image bytes"), options.fileSystem),
                            "image/png", DataSource.NETWORK)
                        val color = reply.color ?: error("Image fetch failed")
                        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.argb(255, (color.red * 255).toInt(),
                            (color.green * 255).toInt(), (color.blue * 255).toInt()))
                        ImageFetchResult(bitmap.asImage(), false, DataSource.NETWORK)
                    } finally { reply.finished.complete(Unit) }
                }
            })
        }.build()
        imageLoader = loader
        previousImageLoader = SingletonImageLoader.get(context)
        SingletonImageLoader.setUnsafe(loader)
        rule.setContent {
            MaterialTheme {
                Column {
                    roles.filterNot { it == "switcher" }.forEach { role ->
                        ProfileImage(
                            model = accountAvatarModel("https://images.example/${address.value}/$role", revision.value),
                            identity = listOf(owner.value, role), retainPreviousImage = true,
                            contentDescription = role, imageLoader = loader,
                            modifier = Modifier.size(48.dp).background(Color.Gray).testTag(role),
                        )
                    }
                    val session = AccountSession(owner.value, "https://${owner.value}.example", "me", "me",
                        "Me", "https://images.example/${address.value}/switcher", "test-token",
                        avatarRevision = revision.value)
                    TimelineTopBar(
                        selectedFeed = TimelineFeed.Home, onFeedSelected = {}, onAnnouncements = {}, onLogout = {},
                        activeSession = session, sessions = listOf(session, session.copy(sessionId = "account-b",
                            instanceUrl = "https://account-b.example", displayName = "Other", avatarRevision = 3)),
                        onAccountSelected = { owner.value = it; revision.value = 3 }, onSettings = {},
                    )
                }
            }
        }
    }

    @After fun restoreImageLoader() {
        replies.values.forEach { it.release.complete(Unit) }
        previousImageLoader?.let { SingletonImageLoader.setUnsafe(it) }
        if (::imageLoader.isInitialized) imageLoader.shutdown()
    }

    private fun waitStarted(requests: List<Reply>) = rule.waitUntil(10_000) { requests.all { it.started.isCompleted } }
    private fun release(requests: List<Reply>) = requests.forEach { it.release.complete(Unit) }
    private fun pixel(role: String): Color {
        val node = if (role == "switcher") rule.onNodeWithContentDescription("アカウントを切り替える", useUnmergedTree = true)
            else rule.onNodeWithTag(role)
        val pixels = node.captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
    private fun waitColor(color: Color) = rule.waitUntil(10_000) { roles.all { pixel(it) == color } }
    private fun assertColor(color: Color) = roles.forEach { assertEquals(color, pixel(it)) }

    @Test fun sameAddressRefreshKeepsBothImagesUntilSuccessEvenAfterMemoryCacheEviction() {
        prepare(1, Color.Red, ready = true)
        val next = prepare(2, Color.Blue)
        val unchanged = prepare(3, Color.Blue)
        render()
        waitColor(Color.Red)
        // Old requests are no longer cache hits; the visible successful painter itself must survive.
        rule.runOnIdle { imageLoader.memoryCache?.clear(); revision.value = 2 }
        waitStarted(next)
        assertColor(Color.Red)
        rule.mainClock.advanceTimeBy(500)
        assertColor(Color.Red)
        release(next)
        waitColor(Color.Blue)
        rule.runOnIdle { revision.value = 3 }
        waitStarted(unchanged)
        assertColor(Color.Blue)
        release(unchanged)
        waitColor(Color.Blue)
    }

    @Test fun changedAddressFailureKeepsOldImagesAndNextRefreshRecovers() {
        prepare(1, Color.Red, ready = true)
        val failed = prepare(2, null, path = "changed")
        val retry = prepare(3, Color.Green, path = "changed")
        render()
        waitColor(Color.Red)
        rule.runOnIdle { address.value = "changed"; revision.value = 2 }
        waitStarted(failed)
        assertColor(Color.Red)
        release(failed)
        rule.waitUntil(10_000) { errors.get() == roles.size }
        rule.waitForIdle()
        assertColor(Color.Red)
        rule.runOnIdle { revision.value = 3 }
        waitStarted(retry)
        assertColor(Color.Red)
        release(retry)
        waitColor(Color.Green)
    }

    @Test fun decodeFailureKeepsLastSuccessfulImages() {
        prepare(1, Color.Red, ready = true)
        val invalid = prepare(2, null, corrupt = true)
        render()
        waitColor(Color.Red)
        rule.runOnIdle { revision.value = 2 }
        waitStarted(invalid)
        assertColor(Color.Red)
        release(invalid)
        rule.waitUntil(10_000) { errors.get() == roles.size }
        rule.waitForIdle()
        assertColor(Color.Red)
    }

    @Test fun laterRefreshWinsOverNonCancellableOlderImageFetch() {
        prepare(1, Color.Red, ready = true)
        val obsolete = prepare(2, Color.Green, nonCancellable = true)
        val current = prepare(3, Color.Blue)
        render()
        waitColor(Color.Red)
        rule.runOnIdle { revision.value = 2 }
        waitStarted(obsolete)
        rule.runOnIdle { revision.value = 3 }
        waitStarted(current)
        assertColor(Color.Red)
        release(current)
        waitColor(Color.Blue)
        release(obsolete)
        rule.waitUntil(10_000) { obsolete.all { it.finished.isCompleted } }
        rule.waitForIdle()
        assertColor(Color.Blue)
    }

    @Test fun switchingOwnerClearsOldImagesAndLateOldRequestCannotReplaceNewImages() {
        prepare(1, Color.Red, ready = true)
        val obsolete = prepare(2, Color.Green, nonCancellable = true)
        val current = prepare(3, Color.Blue)
        render()
        waitColor(Color.Red)
        rule.runOnIdle { revision.value = 2 }
        waitStarted(obsolete)
        assertColor(Color.Red)
        rule.runOnIdle { owner.value = "account-b"; revision.value = 3 }
        waitStarted(current)
        listOf("avatar", "header").forEach { assertEquals(Color.Gray, pixel(it)) }
        assertNotEquals(Color.Red, pixel("switcher"))
        release(current)
        waitColor(Color.Blue)
        release(obsolete)
        rule.waitUntil(10_000) { obsolete.all { it.finished.isCompleted } }
        rule.waitForIdle()
        assertColor(Color.Blue)
    }

    @Test fun retainedTimelineIconStillOpensAccountDialogAndSelectsAnotherAccount() {
        prepare(1, Color.Red, ready = true)
        val current = prepare(3, Color.Blue)
        render()
        waitColor(Color.Red)
        rule.onNodeWithContentDescription("アカウントを切り替える").performClick()
        rule.onNodeWithText("Other").performClick()
        waitStarted(current)
        assertEquals("account-b", owner.value)
        release(current)
        waitColor(Color.Blue)
    }
}
