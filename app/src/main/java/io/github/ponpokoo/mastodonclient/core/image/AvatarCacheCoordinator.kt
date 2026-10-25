package io.github.ponpokoo.mastodonclient.core.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** URL-scoped, public images only. No session, credentials or authenticated client is retained. */
internal class AvatarCacheCoordinator(
    private val store: AvatarValidationStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    maxConcurrent: Int = 3,
) {
    private val stateLock = Mutex()
    // Cache probes and publication must not overlap: Coil snapshots prevent opening an editor.
    val cacheLock = Mutex()
    private val permits = Semaphore(maxConcurrent)
    private class Pending {
        @Volatile var current = true
        lateinit var task: Deferred<Boolean>
    }
    private val running = mutableMapOf<String, Pending>()
    private val jobs = mutableSetOf<Deferred<Boolean>>()
    @Volatile private var epoch = 0L
    private var revision = 0L
    private val mutableRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutableRevisions.asStateFlow()

    suspend fun update(
        url: String,
        hasCachedImage: Boolean,
        force: Boolean = false,
        refresh: suspend (isCurrent: () -> Boolean) -> Boolean,
    ): Deferred<Boolean>? = stateLock.withLock {
        val previous = running[url]
        if (!force && previous != null) return@withLock previous.task
        val validation = store.read(url)
        val time = now()
        val recentlyValidated = validation.validatedAt > 0 && time >= validation.validatedAt &&
            time - validation.validatedAt < VALIDATION_INTERVAL
        val backingOff = validation.retryAfter > time && validation.retryAfter - time <= RETRY_INTERVAL
        if (!force && (backingOff || hasCachedImage && recentlyValidated)) return@withLock null
        val startedEpoch = epoch
        val pending = Pending()
        // An explicit refresh after an edit must not adopt a download begun before the edit.
        previous?.current = false
        pending.task = scope.async(start = CoroutineStart.LAZY) {
            try {
                previous?.task?.join()
                val success = try {
                    permits.withPermit {
                        epoch == startedEpoch && pending.current && refresh { epoch == startedEpoch && pending.current }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                stateLock.withLock {
                    if (epoch != startedEpoch || !pending.current) return@withLock false
                    store.write(url, if (success) AvatarValidation(validatedAt = now())
                        else validation.copy(retryAfter = now() + RETRY_INTERVAL))
                    if (success) {
                        mutableRevisions.value = (mutableRevisions.value - url).toList()
                            .takeLast(MAX_REVISIONS - 1).toMap() + (url to ++revision)
                    }
                    success
                }
            } finally {
                withContext(NonCancellable) {
                    stateLock.withLock {
                        if (running[url] === pending) running.remove(url)
                        jobs.remove(pending.task)
                    }
                }
            }
        }
        running[url] = pending
        jobs.add(pending.task)
        pending.task.also { it.start() }
    }

    suspend fun clear(clearImages: () -> Unit) = stateLock.withLock {
        ++epoch
        jobs.forEach { it.cancel() }
        jobs.clear()
        running.clear()
        cacheLock.withLock {
            clearImages()
            store.clear()
            mutableRevisions.value = emptyMap()
        }
    }

    companion object {
        const val VALIDATION_INTERVAL = 24 * 60 * 60 * 1000L
        const val RETRY_INTERVAL = 5 * 60 * 1000L
        private const val MAX_REVISIONS = 4096
    }
}
