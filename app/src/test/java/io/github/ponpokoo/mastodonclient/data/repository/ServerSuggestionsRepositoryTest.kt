package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.local.ServerCatalogCache
import io.github.ponpokoo.mastodonclient.data.local.ServerSuggestionsLocalDataSource
import io.github.ponpokoo.mastodonclient.data.remote.ServerCatalogSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServerSuggestionsRepositoryTest {
    private val week = 7 * 24 * 60 * 60 * 1000L
    private class Local : ServerSuggestionsLocalDataSource {
        var cache: ServerCatalogCache? = null
        var corrupt = false
        var failWrite = false
        override suspend fun readCache(): ServerCatalogCache? {
            if (corrupt) throw IOException("corrupt cache")
            return cache
        }
        override suspend fun writeCache(cache: ServerCatalogCache) {
            if (failWrite) throw IOException("disk full")
            this.cache = cache
        }
        override suspend fun bundledDomains() = listOf("supplement.example", "HTTPS://OLD.EXAMPLE/")
    }

    @Test fun cachedCandidatesAppearBeforeRefreshAndCatalogIsNormalized() = runTest {
        val local = Local().apply { cache = ServerCatalogCache(listOf("old.example"), 10) }
        val response = CompletableDeferred<List<String>>()
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource { response.await() }, local, { week + 10 })
        val values = mutableListOf<List<String>>()
        val job = launch { repo.observeDomains().toList(values) }
        testScheduler.runCurrent()
        assertEquals(listOf(listOf("supplement.example", "old.example")), values)
        response.complete(listOf("https://NEW.EXAMPLE/", "new.example", "https://bad.example/path", "http://bad.example"))
        job.join()
        assertEquals(listOf("supplement.example", "old.example", "new.example"), values.last())
        assertEquals(listOf("new.example"), local.cache!!.domains)
    }

    @Test fun aWeekOldCacheRefreshesExactlyOnceAndFreshCacheSurvivesRepositoryRecreation() = runTest {
        val local = Local().apply { cache = ServerCatalogCache(listOf("cached.example"), 10) }
        var time = week + 9
        var requests = 0
        val source = ServerCatalogSource { requests++; listOf("updated.example") }
        val repo = DefaultServerSuggestionsRepository(source, local, { time })
        repo.observeDomains().toList()
        assertEquals(0, requests)
        time++
        repo.observeDomains().toList()
        repo.observeDomains().toList()
        DefaultServerSuggestionsRepository(source, local, { time }).observeDomains().toList()
        assertEquals(1, requests)
        time += week
        repo.observeDomains().toList()
        assertEquals(2, requests)
    }

    @Test fun failureKeepsOldCatalogAndSuppressesRepeatedAttemptsWithinAWeek() = runTest {
        val original = ServerCatalogCache(listOf("cached.example"), 10)
        val local = Local().apply { cache = original }
        var time = week + 10
        var requests = 0
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource { requests++; throw IOException() }, local, { time })
        repeat(2) {
            assertTrue(repo.observeDomains().toList().last().contains("cached.example"))
        }
        assertEquals(1, requests)
        assertEquals(original, local.cache)
        time += week
        repo.observeDomains().toList()
        assertEquals(2, requests)
    }

    @Test fun corruptCacheAndFailedPersistenceDoNotHideBundledOrFetchedDomains() = runTest {
        val local = Local().apply { corrupt = true; failWrite = true }
        var requests = 0
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource { requests++; listOf("fetched.example") }, local, { 100 })
        val values = repo.observeDomains().toList()
        assertEquals(listOf("supplement.example", "old.example"), values.first())
        assertTrue(values.last().contains("fetched.example"))
        assertTrue(repo.observeDomains().toList().last().contains("fetched.example"))
        assertEquals(1, requests)
    }

    @Test fun emptyResponseDoesNotEraseExistingCache() = runTest {
        val original = ServerCatalogCache(listOf("cached.example"), 10)
        val local = Local().apply { cache = original }
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource { emptyList() }, local, { week + 10 })
        assertTrue(repo.observeDomains().toList().last().contains("cached.example"))
        assertEquals(original, local.cache)
    }

    @Test fun leavingScreenCancelsRefreshAndAllowsTheNextScreenToRetry() = runTest {
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val local = Local()
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource {
            requests++
            if (requests == 1) { started.complete(Unit); CompletableDeferred<Unit>().await() }
            listOf("fetched.example")
        }, local, { 100 })
        val job = launch { repo.observeDomains().toList() }
        started.await()
        job.cancelAndJoin()
        assertNull(local.cache)
        val retried = repo.observeDomains().toList().last()
        assertEquals("A cancelled refresh must not suppress the next attempt", 2, requests)
        assertTrue(retried.toString(), retried.contains("fetched.example"))
    }

    @Test fun concurrentScreensShareOneRefreshAndClockRollbackDoesNotLockOutRefresh() = runTest {
        val local = Local().apply { cache = ServerCatalogCache(listOf("old.example"), 500) }
        var requests = 0
        val response = CompletableDeferred<List<String>>()
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource { requests++; response.await() }, local, { 100 })
        val jobs = List(2) { launch { repo.observeDomains().toList() } }
        testScheduler.runCurrent()
        assertEquals(1, requests)
        response.complete(listOf("new.example"))
        jobs.forEach { it.join() }
        assertEquals(1, requests)
    }

    @Test fun nonCooperativeResponseAfterLeavingDoesNotUpdateTheCache() = runTest {
        val local = Local()
        val response = CompletableDeferred<List<String>>()
        val repo = DefaultServerSuggestionsRepository(ServerCatalogSource {
            withContext(NonCancellable) { response.await() }
        }, local, { 100 })
        val job = launch { repo.observeDomains().toList() }
        testScheduler.runCurrent()
        job.cancel()
        response.complete(listOf("late.example"))
        job.join()
        assertNull(local.cache)
    }
}
