package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.annotation.DelicateCoilApi
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.ponpokoo.mastodonclient.core.image.*
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import io.github.ponpokoo.mastodonclient.feature.common.ProfileImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoilApi::class, DelicateCoilApi::class)
class AvatarImageCacheDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sameUrlRefreshKeepsOldImageThenUpdatesVisibleImageAndRejectsBadBytes() = runBlocking {
        Fixture().use { fixture ->
            val images = fixture.images
            assertEquals(Color.RED, fixture.pixel(fixture.load()))
            repeat(3) { assertEquals(Color.RED, fixture.pixel(fixture.load())) }
            assertEquals(1, fixture.downloads.get())
            fixture.loader.memoryCache?.clear()
            assertEquals(Color.RED, fixture.pixel(fixture.load()))
            assertEquals(1, fixture.downloads.get())

            rule.setContent {
                val revisions by images.coordinator.revisions.collectAsStateWithLifecycle()
                AsyncImage(images.request(fixture.url, revisions[fixture.url] ?: 0), null,
                    modifier = Modifier.size(48.dp).testTag("cached_avatar"), imageLoader = fixture.loader)
            }
            waitForPixel(Color.RED)
            fixture.time += AvatarCacheCoordinator.VALIDATION_INTERVAL
            fixture.bytes = png(Color.BLUE)
            fixture.gate = CompletableDeferred()
            val oldRevision = images.coordinator.revisions.value[fixture.url]
            assertEquals(Color.RED, fixture.pixel(fixture.load()))
            fixture.awaitDownloads(2)
            waitForPixel(Color.RED)
            fixture.gate!!.complete(Unit)
            withTimeout(5_000) { while (images.coordinator.revisions.value[fixture.url] == oldRevision) delay(10) }
            waitForPixel(Color.BLUE)

            fixture.time += AvatarCacheCoordinator.VALIDATION_INTERVAL
            fixture.gate = null
            fixture.bytes = "not an image".toByteArray()
            assertEquals(Color.BLUE, fixture.pixel(fixture.load()))
            fixture.awaitDownloads(3)
            withTimeout(5_000) { while (fixture.store.read(fixture.url).retryAfter == 0L) delay(10) }
            // Check disk too: a decode failure must never overwrite the last successful bytes.
            fixture.loader.memoryCache?.clear()
            assertEquals(Color.BLUE, fixture.pixel(fixture.load()))
            assertEquals(3, fixture.downloads.get())
            waitForPixel(Color.BLUE)
        }
    }

    @Test fun cacheClearRefetchesAndExistingUrlCacheIsAdoptedWithoutChangingItsKey() = runBlocking {
        Fixture().use { fixture ->
            // Seed the exact URL entry used by the old Coil setup, with no Nagisa timestamp.
            fixture.seed(Color.RED)
            fixture.gate = CompletableDeferred()
            assertEquals(Color.RED, fixture.pixel(fixture.load()))
            fixture.awaitDownloads(1)
            fixture.gate!!.complete(Unit)
            withTimeout(5_000) { while (fixture.store.read(fixture.url).validatedAt == 0L) delay(10) }
            val original = SingletonImageLoader.get(fixture.context)
            try {
                SingletonImageLoader.setUnsafe(fixture.loader)
                fixture.images.clear()
                fixture.bytes = png(Color.GREEN)
                fixture.gate = null
                assertEquals(Color.GREEN, fixture.pixel(fixture.load()))
                assertEquals(2, fixture.downloads.get())
            } finally { SingletonImageLoader.setUnsafe(original) }
        }
    }

    @Test fun explicitSelfRefreshSharesUrlCacheKeepsPainterAndCanRetryAfterFailure() = runBlocking {
        Fixture().use { fixture ->
            assertEquals(Color.RED, fixture.pixel(fixture.load()))
            val images = fixture.images
            rule.setContent {
                val revisions by images.coordinator.revisions.collectAsStateWithLifecycle()
                ProfileImage(images.request(fixture.url, revisions[fixture.url] ?: 0),
                    identity = "self", retainPreviousImage = true, contentDescription = null,
                    modifier = Modifier.size(48.dp).testTag("cached_avatar"), imageLoader = fixture.loader)
            }
            waitForPixel(Color.RED)
            val original = SingletonImageLoader.get(fixture.context)
            try {
                SingletonImageLoader.setUnsafe(fixture.loader)
                fixture.bytes = png(Color.BLUE)
                fixture.gate = CompletableDeferred()
                val refresh = async(Dispatchers.IO) { images.revalidate(fixture.url) }
                fixture.awaitDownloads(2)
                fixture.loader.memoryCache?.clear()
                waitForPixel(Color.RED)
                fixture.gate!!.complete(Unit)
                assertTrue(refresh.await())
                waitForPixel(Color.BLUE)
                fixture.gate = null
                fixture.bytes = "bad image".toByteArray()
                assertFalse(images.revalidate(fixture.url))
                waitForPixel(Color.BLUE)
                fixture.loader.memoryCache?.clear()
                assertEquals(Color.BLUE, fixture.pixel(fixture.load()))
                assertEquals(3, fixture.downloads.get())
                // User-requested refresh bypasses the normal five-minute retry backoff.
                fixture.bytes = png(Color.GREEN)
                assertTrue(images.revalidate(fixture.url))
                waitForPixel(Color.GREEN)
                assertEquals(Color.GREEN, fixture.pixel(fixture.load()))
                assertEquals(4, fixture.downloads.get())
            } finally { SingletonImageLoader.setUnsafe(original) }
        }
    }

    @Test fun systemNotificationPostsBeforeDownloadSharesUiCacheAndDoesNotReviveDismissedNotification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Fixture().use { fixture ->
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
                fixture.context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            val original = SingletonImageLoader.get(fixture.context)
            val session = AccountSession("avatar-test-${UUID.randomUUID()}", "https://instance.example", "me",
                "me", "Me", "", "synthetic-token")
            val manager = fixture.context.getSystemService(NotificationManager::class.java)
            val source = SystemNotificationDataSource(fixture.context) { listOf(session) }
            fun notification(id: String) = TimelineNotification(id, "favourite", "",
                StatusAuthor("actor", "Actor", "actor", fixture.url), null)
            fun visible(id: String) = manager.activeNotifications.singleOrNull { it.tag == "${session.sessionId}:$id" }
            try {
                SingletonImageLoader.setUnsafe(fixture.loader)
                fixture.gate = CompletableDeferred()
                val posting = async(Dispatchers.IO) { source.showNotification(session, notification("first")) }
                fixture.awaitDownloads(1)
                assertNotNull(visible("first"))
                assertNull(visible("first")!!.notification.getLargeIcon())
                manager.cancel("${session.sessionId}:first", 0)
                fixture.gate!!.complete(Unit)
                posting.await()
                assertNull(visible("first"))
                source.showNotification(session, notification("second"))
                withTimeout(2_000) { while (visible("second")?.notification?.getLargeIcon() == null) delay(10) }
                assertNotNull(visible("second")!!.notification.getLargeIcon())
                assertEquals(Color.RED, fixture.pixel(fixture.load()))
                assertEquals(1, fixture.downloads.get())
                // And the opposite direction: a UI cache hit is also a notification cache hit.
                source.showNotification(session, notification("third"))
                withTimeout(2_000) { while (visible("third")?.notification?.getLargeIcon() == null) delay(10) }
                assertNotNull(visible("third")!!.notification.getLargeIcon())
                assertEquals(1, fixture.downloads.get())
            } finally {
                listOf("first", "second", "third").forEach { manager.cancel("${session.sessionId}:$it", 0) }
                fixture.context.getSharedPreferences("delivered_notifications", 0).edit().remove(session.sessionId).commit()
                SingletonImageLoader.setUnsafe(original)
            }
        }
    }

    private fun waitForPixel(color: Int) {
        rule.waitUntil(5_000) {
            val pixels = rule.onNodeWithTag("cached_avatar").captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2] == androidx.compose.ui.graphics.Color(color)
        }
    }

    private class Store : AvatarValidationStore {
        private val entries = java.util.concurrent.ConcurrentHashMap<String, AvatarValidation>()
        override suspend fun read(url: String) = entries[url] ?: AvatarValidation()
        override suspend fun write(url: String, validation: AvatarValidation) { entries[url] = validation }
        override suspend fun clear() { entries.clear() }
    }

    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://avatar.example/${UUID.randomUUID()}.png"
        val store = Store()
        @Volatile var time = 1_000L
        @Volatile var bytes = png(Color.RED)
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val downloads = AtomicInteger()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val images = AvatarImages(context, store, scope, { time })
        private val directory = File(context.cacheDir, "avatar-cache-test-${UUID.randomUUID()}")
        val loader: ImageLoader
        init {
            val client = object : NetworkClient {
                override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                    downloads.incrementAndGet()
                    assertEquals("no-cache", request.headers["Cache-Control"])
                    assertNull(request.headers["Authorization"])
                    gate?.await()
                    return block(NetworkResponse(headers = NetworkHeaders.Builder().set("Content-Type", "image/png").build(),
                        body = NetworkResponseBody(Buffer().write(bytes))))
                }
            }
            lateinit var built: ImageLoader
            built = ImageLoader.Builder(context)
                .diskCache { DiskCache.Builder().directory(directory.absolutePath.toPath()).maxSizeBytes(8 * 1024 * 1024).build() }
                .components {
                    add(images.interceptor { built })
                    add(Fetcher.Factory<AvatarDownload> { data, options, imageLoader ->
                        NetworkFetcher(data.url, options, lazy { client }, lazy { imageLoader.diskCache },
                            lazy { CacheStrategy.DEFAULT }, ConnectivityChecker.ONLINE)
                    })
                }.build()
            loader = built
        }
        suspend fun load() = loader.execute(images.request(url).newBuilder().size(128).allowHardware(false).build())
        fun pixel(result: coil3.request.ImageResult): Int {
            assertTrue("Expected success, got $result", result is SuccessResult)
            return result.image!!.toBitmap().getPixel(0, 0)
        }
        suspend fun awaitDownloads(count: Int) = withTimeout(5_000) { while (downloads.get() < count) delay(10) }
        fun seed(color: Int) {
            val disk = loader.diskCache!!
            val editor = disk.openEditor(url)!!
            disk.fileSystem.write(editor.metadata) { writeUtf8("") }
            disk.fileSystem.write(editor.data) { write(png(color)) }
            editor.commit()
        }
        override fun close() {
            gate?.complete(Unit)
            scope.cancel()
            loader.shutdown()
            directory.deleteRecursively()
        }
    }

    companion object {
        private fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
