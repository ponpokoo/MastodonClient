package io.github.ponpokoo.mastodonclient.core.image

import android.content.Context
import android.annotation.SuppressLint
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.fetch.Fetcher
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.toUri
import io.github.ponpokoo.mastodonclient.core.preferences.userPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The revision changes the Compose request identity, never the URL-based Coil cache keys. */
internal data class AvatarImage(val url: String, val revision: Long = 0)
internal data class AvatarDownload(val url: String)

internal class AvatarImages(
    context: Context,
    store: AvatarValidationStore = PreferencesAvatarValidationStore(context.applicationContext.userPreferencesDataStore),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    now: () -> Long = System::currentTimeMillis,
) {
    private val context = context.applicationContext
    val coordinator = AvatarCacheCoordinator(
        store, scope, now,
    )

    fun request(url: String, revision: Long = 0): ImageRequest =
        ImageRequest.Builder(context).data(AvatarImage(url, revision))
            .placeholderMemoryCacheKey(url).build()

    suspend fun revalidate(url: String): Boolean = withContext(Dispatchers.IO) {
        coordinator.update(url, hasCachedImage = true, force = true) { isCurrent ->
            refresh(SingletonImageLoader.get(context), url, isCurrent)
        }?.await() == true
    }

    fun interceptor(loader: () -> ImageLoader) = Interceptor { chain ->
        val avatar = chain.request.data as? AvatarImage ?: return@Interceptor chain.proceed()
        withContext(Dispatchers.IO) {
            val cachedRequest = chain.request.newBuilder().data(avatar.url)
                .memoryCacheKey(avatar.url).diskCacheKey(avatar.url)
                .networkCachePolicy(CachePolicy.DISABLED).build()
            suspend fun cached(): ImageResult = coordinator.cacheLock.withLock {
                chain.withRequest(cachedRequest).proceed()
            }
            val previous = cached()
            val update = try {
                coordinator.update(avatar.url, previous is SuccessResult) { isCurrent ->
                    refresh(loader(), avatar.url, isCurrent)
                }
            } catch (_: IOException) {
                // Preferences failure must not hide an already cached icon.
                null
            }
            val result = if (previous is SuccessResult) previous else {
                if (update?.await() == true) cached() else previous
            }
            when (result) {
                is SuccessResult -> result.copy(request = chain.request)
                is ErrorResult -> result.copy(request = chain.request)
            }
        }
    }

    /** Decode a temporary Coil entry first; a bad response must not replace a usable old image. */
    private suspend fun refresh(loader: ImageLoader, url: String, isCurrent: () -> Boolean): Boolean {
        val disk = loader.diskCache ?: return false
        val stagingKey = "avatar-refresh:${UUID.randomUUID()}"
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(AvatarDownload(url))
                .size(512).memoryCachePolicy(CachePolicy.DISABLED)
                .diskCacheKey(stagingKey).diskCachePolicy(CachePolicy.WRITE_ONLY).build())
            if (result !is SuccessResult) return false
            val published = coordinator.cacheLock.withLock {
                if (!isCurrent()) return@withLock false
                val snapshot = disk.openSnapshot(stagingKey) ?: return@withLock false
                snapshot.use {
                    val editor = disk.openEditor(url) ?: return@withLock false
                    try {
                        disk.fileSystem.source(snapshot.metadata).use { input ->
                            disk.fileSystem.sink(editor.metadata).buffer().use { it.writeAll(input) }
                        }
                        disk.fileSystem.source(snapshot.data).use { input ->
                            disk.fileSystem.sink(editor.data).buffer().use { it.writeAll(input) }
                        }
                        editor.commit()
                    } catch (error: Exception) {
                        editor.abort()
                        throw error
                    }
                }
                // Invalidate every decoded size of this URL, leaving all other image types alone.
                loader.memoryCache?.let { memory ->
                    memory.keys.filter { it.key == url }.forEach(memory::remove)
                }
                true
            }
            if (published && isCurrent()) {
                // Make the next Compose request's loading placeholder available before notifying it.
                coordinator.cacheLock.withLock {
                    if (isCurrent()) loader.execute(ImageRequest.Builder(context).data(url).size(512)
                        .memoryCacheKey(url).diskCacheKey(url)
                        .networkCachePolicy(CachePolicy.DISABLED).build())
                }
            }
            return published
        } finally {
            disk.remove(stagingKey)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        coordinator.clear {
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }

    companion object {
        // The retained context is always applicationContext, never an Activity or service.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AvatarImages? = null
        fun get(context: Context): AvatarImages = instance ?: synchronized(this) {
            instance ?: AvatarImages(context).also { instance = it }
        }
    }
}

/** Only avatar downloads use these limits. Other Coil network requests keep their existing setup. */
internal class AvatarDownloadFetcherFactory : Fetcher.Factory<AvatarDownload> {
    private val delegate = OkHttpNetworkFetcherFactory(
        callFactory = {
            OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(AvatarResponseLimitInterceptor()).build()
        },
    )

    override fun create(data: AvatarDownload, options: Options, imageLoader: ImageLoader): Fetcher? =
        delegate.create(data.url.toUri(), options, imageLoader)
}
