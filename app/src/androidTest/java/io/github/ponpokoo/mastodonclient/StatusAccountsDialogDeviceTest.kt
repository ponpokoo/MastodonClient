package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.feature.detail.StatusAccountsDialog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class StatusAccountsDialogDeviceTest {
    @get:Rule val rule = createComposeRule()
    private fun account(index: Int) = StatusAuthor("account-$index", "Account $index", "user$index", "")
    private val fetches = AtomicInteger()
    private val releaseImages = CompletableDeferred<Unit>()
    private var releaseImagesForAppend: CompletableDeferred<Unit>? = null
    private var previousLoader: ImageLoader? = null
    private var testLoader: ImageLoader? = null

    private fun prepareImages(): ImageLoader {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { data, _, _ ->
                if (!data.toString().startsWith("https://avatars.example/")) null else Fetcher {
                    fetches.incrementAndGet()
                    releaseImages.await()
                    releaseImagesForAppend?.await()
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.GREEN)
                    ImageFetchResult(bitmap.asImage(), false, DataSource.NETWORK)
                }
            })
        }.build()
        previousLoader = SingletonImageLoader.get(context)
        testLoader = loader
        SingletonImageLoader.setUnsafe(loader)
        return loader
    }

    @After fun restoreImages() {
        rule.mainClock.autoAdvance = true
        releaseImages.complete(Unit)
        releaseImagesForAppend?.complete(Unit)
        previousLoader?.let { SingletonImageLoader.setUnsafe(it) }
        testLoader?.shutdown()
    }

    private fun avatarPixel(index: Int): Color {
        val pixels = rule.onNodeWithTag("status_account_avatar_account-$index", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test fun namesAreVisibleAndClickableWhileAvatarRequestsArePendingWithoutAdvancingFrames() {
        prepareImages()
        val opened = AtomicReference<String?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                StatusAccountsDialog(
                    title = "お気に入りしたアカウント",
                    accounts = (0..2).map { account(it).copy(avatarUrl = "https://avatars.example/$it") },
                    isLoading = false, onDismiss = {}, onAccountClick = opened::set,
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        // Frozen Compose time makes an added frame wait or artificial delay fail this check.
        rule.waitUntil(10_000) { fetches.get() == 3 }
        (0..2).forEach { rule.onNodeWithText("Account $it").assertIsDisplayed() }
        assertNotEquals(Color.Green, avatarPixel(0))
        rule.onNodeWithText("Account 1").performClick()
        assertEquals("account-1", opened.get())
        rule.onNodeWithText("Account 0").assertIsDisplayed()
        assertNotEquals(Color.Green, avatarPixel(0))
        releaseImages.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(10_000) { avatarPixel(0) == Color.Green }
    }

    @Test fun cachedAvatarsAreReusedAndAppendedRowsLoadWithoutHidingExistingImages() {
        val loader = prepareImages()
        releaseImages.complete(Unit)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = account(0).copy(avatarUrl = "https://avatars.example/0")
        val cached = runBlocking { loader.execute(ImageRequest.Builder(context).data(first.avatarUrl).size(256).build()) }
        assertTrue(cached is SuccessResult)
        val accounts = mutableStateOf(listOf(first))
        rule.setContent {
            MaterialTheme {
                StatusAccountsDialog(title = "リアクションした人", accounts = accounts.value,
                    isLoading = false, onDismiss = {}, onAccountClick = {})
            }
        }
        rule.onNodeWithText("Account 0").assertIsDisplayed()
        rule.waitUntil(10_000) { avatarPixel(0) == Color.Green }
        assertEquals(1, fetches.get())
        val appendedImage = CompletableDeferred<Unit>()
        releaseImagesForAppend = appendedImage
        rule.runOnIdle { accounts.value += account(1).copy(avatarUrl = "https://avatars.example/1") }
        rule.waitUntil(10_000) { fetches.get() == 2 }
        rule.onNodeWithText("Account 1").assertIsDisplayed()
        assertEquals(Color.Green, avatarPixel(0))
        assertNotEquals(Color.Green, avatarPixel(1))
        appendedImage.complete(Unit)
        rule.waitUntil(10_000) { avatarPixel(1) == Color.Green }
        assertEquals(2, fetches.get())
    }

    @Test fun scrollingToTheEndLoadsMoreAndAppendedAccountCanOpenItsProfile() {
        val accounts = mutableStateOf((0 until 40).map(::account))
        val loading = mutableStateOf(false)
        val cursor = mutableStateOf<String?>("server-cursor")
        val loads = AtomicInteger()
        val opened = AtomicReference<String?>(null)
        rule.setContent {
            MaterialTheme {
                StatusAccountsDialog(
                    title = "お気に入りしたアカウント", accounts = accounts.value, isLoading = loading.value,
                    nextMaxId = cursor.value,
                    onLoadMore = { loads.incrementAndGet(); loading.value = true },
                    onDismiss = {}, onAccountClick = opened::set,
                )
            }
        }
        rule.runOnIdle { assertEquals(0, loads.get()) }
        rule.onNodeWithTag("status_accounts_load_more").assertDoesNotExist()
        rule.onNodeWithTag("status_accounts_list").performScrollToIndex(39)
        rule.waitUntil { loads.get() == 1 }
        rule.onNodeWithTag("status_accounts_list").performScrollToIndex(40)
        rule.onNodeWithTag("status_accounts_load_more").assertIsDisplayed()
        rule.onNodeWithText("Account 39").assertIsDisplayed()
        rule.runOnIdle {
            accounts.value += account(40)
            cursor.value = null
            loading.value = false
        }
        rule.onNodeWithTag("status_accounts_load_more").assertDoesNotExist()
        rule.onNodeWithTag("status_accounts_list").performScrollToIndex(40)
        rule.onNodeWithText("Account 40").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals("account-40", opened.get())
            assertEquals(1, loads.get())
        }
    }

    @Test fun additionalPageFailureKeepsTheListAndOffersExplicitRetryAtTheEnd() {
        val error = mutableStateOf<String?>(null)
        val cursor = mutableStateOf<String?>("failed-cursor")
        val loads = AtomicInteger()
        val retries = AtomicInteger()
        rule.setContent {
            MaterialTheme {
                StatusAccountsDialog(
                    title = "ブーストしたアカウント", accounts = (0 until 40).map(::account), isLoading = false,
                    nextMaxId = cursor.value, errorMessage = error.value,
                    onLoadMore = { loads.incrementAndGet(); error.value = "offline" },
                    onRetry = { retries.incrementAndGet(); cursor.value = null; error.value = null },
                    onDismiss = {}, onAccountClick = {},
                )
            }
        }
        rule.onNodeWithTag("status_accounts_list").performScrollToIndex(39)
        rule.waitUntil { loads.get() == 1 }
        rule.onNodeWithText("Account 39").assertIsDisplayed()
        rule.onNodeWithTag("status_accounts_list").performScrollToIndex(40)
        rule.onNodeWithText("再試行").performClick()
        rule.runOnIdle { assertEquals(1, retries.get()); assertEquals(1, loads.get()) }
    }
}
