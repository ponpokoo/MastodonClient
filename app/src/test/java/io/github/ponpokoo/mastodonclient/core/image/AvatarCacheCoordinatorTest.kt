package io.github.ponpokoo.mastodonclient.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AvatarCacheCoordinatorTest {
    @Test fun expiresOnNextAccessAt24HoursAndRefillsEvictedImagesBeforeThen() = runTest {
        val store = MemoryValidationStore()
        var time = 1_000L
        val cache = AvatarCacheCoordinator(store, backgroundScope, { time })
        var fetched = 0
        suspend fun access(cached: Boolean = true, force: Boolean = false) =
            cache.update("url", cached, force) { fetched++; true }?.await()
        assertEquals(true, access(false))
        time += AvatarCacheCoordinator.VALIDATION_INTERVAL - 1
        assertNull(access())
        assertEquals(1, fetched)
        time++
        // Time passing itself does not start work.
        runCurrent()
        assertEquals(1, fetched)
        assertEquals(true, access())
        assertEquals(true, access(cached = false))
        assertEquals(true, access(force = true))
        assertEquals(4, fetched)
    }

    @Test fun failedRefreshKeepsSuccessfulTimestampAndBacksOffAcrossRestart() = runTest {
        val store = MemoryValidationStore()
        var time = 1_000L
        var cache = AvatarCacheCoordinator(store, backgroundScope, { time })
        cache.update("url", false) { true }!!.await()
        time += AvatarCacheCoordinator.VALIDATION_INTERVAL
        assertFalse(cache.update("url", true) { throw java.io.IOException("offline") }!!.await())
        assertEquals(1_000L, store.read("url").validatedAt)
        cache = AvatarCacheCoordinator(store, backgroundScope, { time })
        assertNull(cache.update("url", true) { error("Backoff must survive restart") })
        assertNull(cache.update("url", false) { error("Cache misses must also back off") })
        time += AvatarCacheCoordinator.RETRY_INTERVAL
        assertTrue(cache.update("url", true) { true }!!.await())
        assertEquals(time, store.read("url").validatedAt)
        assertEquals(0L, store.read("url").retryAfter)
    }

    @Test fun overlappingRequestsShareOneDownloadAndWaiterCancellationDoesNotCancelIt() = runTest {
        val cache = AvatarCacheCoordinator(MemoryValidationStore(), backgroundScope, { 1_000L })
        val release = CompletableDeferred<Unit>()
        var fetched = 0
        val first = cache.update("url", false) { fetched++; release.await(); true }!!
        runCurrent()
        val second = cache.update("url", false) { error("Duplicate download") }!!
        assertSame(first, second)
        val waiter = async { second.await() }
        waiter.cancel()
        release.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, fetched)
    }

    @Test fun manyExpiredUrlsAreLimitedTogetherIncludingColdMisses() = runTest {
        val cache = AvatarCacheCoordinator(MemoryValidationStore(), backgroundScope, { 1_000L }, maxConcurrent = 3)
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val downloads = (1..20).map { id ->
            cache.update("url$id", id % 2 == 0) {
                active++
                peak = maxOf(peak, active)
                release.await()
                active--
                true
            }!!
        }
        runCurrent()
        assertEquals(3, active)
        release.complete(Unit)
        assertTrue(downloads.awaitAll().all { it })
        assertEquals(3, peak)
    }

    @Test fun clearRejectsLatePublicationAndAllowsImmediateRefetch() = runTest {
        val store = MemoryValidationStore()
        val cache = AvatarCacheCoordinator(store, backgroundScope, { 1_000L })
        val release = CompletableDeferred<Unit>()
        var published = false
        val old = cache.update("url", false) { isCurrent ->
            withContext(NonCancellable) {
                release.await()
                cache.cacheLock.withLock { if (isCurrent()) published = true }
            }
            true
        }!!
        runCurrent()
        var imagesCleared = false
        cache.clear { imagesCleared = true }
        assertTrue(imagesCleared)
        assertTrue(cache.revisions.value.isEmpty())
        release.complete(Unit)
        old.join()
        assertFalse(published)
        assertEquals(AvatarValidation(), store.read("url"))
        assertTrue(cache.update("url", false) { true }!!.await())
    }

    @Test fun clockMovingBackDoesNotMakeAnImageFreshIndefinitely() = runTest {
        val store = MemoryValidationStore()
        store.write("url", AvatarValidation(validatedAt = 10_000_000, retryAfter = 20_000_000))
        val cache = AvatarCacheCoordinator(store, backgroundScope, { 1_000L })
        assertTrue(cache.update("url", true) { true }!!.await())
    }

    @Test fun explicitRevalidationBypassesFailureBackoffAndInvalidatesPreEditDownload() = runTest {
        val store = MemoryValidationStore()
        val cache = AvatarCacheCoordinator(store, backgroundScope, { 1_000L })
        val release = CompletableDeferred<Unit>()
        var obsoletePublished = false
        val old = cache.update("url", true) { isCurrent ->
            release.await()
            obsoletePublished = isCurrent()
            true
        }!!
        runCurrent()
        var forced = false
        val new = cache.update("url", true, force = true) { forced = true; false }!!
        runCurrent()
        assertFalse(forced)
        release.complete(Unit)
        assertFalse(old.await())
        assertFalse(new.await())
        assertFalse(obsoletePublished)
        assertTrue(forced)
        assertNull(cache.update("url", false) { error("Normal requests must still back off") })
        assertTrue(cache.update("url", true, force = true) { true }!!.await())
    }

    @Test fun clearCancelsBothRunningAndQueuedExplicitRefreshes() = runTest {
        val cache = AvatarCacheCoordinator(MemoryValidationStore(), backgroundScope, { 1_000L })
        val release = CompletableDeferred<Unit>()
        val old = cache.update("url", true) { release.await(); true }!!
        runCurrent()
        var forced = false
        val new = cache.update("url", true, force = true) { forced = true; true }!!
        runCurrent()
        cache.clear { }
        release.complete(Unit)
        old.join(); new.join()
        assertTrue(old.isCancelled)
        assertTrue(new.isCancelled)
        assertFalse(forced)
    }
}

internal class MemoryValidationStore : AvatarValidationStore {
    private val entries = mutableMapOf<String, AvatarValidation>()
    override suspend fun read(url: String) = entries[url] ?: AvatarValidation()
    override suspend fun write(url: String, validation: AvatarValidation) { entries[url] = validation }
    override suspend fun clear() = entries.clear()
}
