package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.network.InstanceUrlNormalizer
import io.github.ponpokoo.mastodonclient.data.local.ServerCatalogCache
import io.github.ponpokoo.mastodonclient.data.local.ServerSuggestionsLocalDataSource
import io.github.ponpokoo.mastodonclient.data.remote.ServerCatalogSource
import io.github.ponpokoo.mastodonclient.domain.repository.ServerSuggestionsRepository
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DefaultServerSuggestionsRepository(
    private val remote: ServerCatalogSource,
    private val local: ServerSuggestionsLocalDataSource,
    private val now: () -> Long = System::currentTimeMillis,
) : ServerSuggestionsRepository {
    private val mutex = Mutex()
    private var memoryCache: ServerCatalogCache? = null
    private var lastAttempt: Long? = null

    override fun observeDomains() = flow {
        val bundled = normalize(runCatchingCancellable { local.bundledDomains() }.getOrDefault(emptyList()))
        val cached = mutex.withLock {
            memoryCache ?: runCatchingCancellable { local.readCache() }.getOrNull()?.also { memoryCache = it }
        }
        emit(normalize(bundled + cached?.domains.orEmpty()))
        val refreshed = mutex.withLock {
            val time = now()
            val current = memoryCache
            val fresh = current != null && time >= current.fetchedAt && time - current.fetchedAt < WEEK
            val recentlyAttempted = lastAttempt?.let { time >= it && time - it < WEEK } == true
            if (fresh || recentlyAttempted) return@withLock current
            lastAttempt = time
            try {
                runCatchingCancellable {
                    val domains = normalize(remote.loadDomains())
                    currentCoroutineContext().ensureActive()
                    // A broken/empty directory response must not erase a usable catalog.
                    check(domains.isNotEmpty())
                    ServerCatalogCache(domains, now())
                }.getOrNull()?.also {
                    memoryCache = it
                    runCatchingCancellable { local.writeCache(it) }
                } ?: current
            } catch (cancelled: CancellationException) {
                lastAttempt = null
                throw cancelled
            }
        }
        emit(normalize(bundled + refreshed?.domains.orEmpty()))
    }

    private fun normalize(domains: List<String>): List<String> = domains.mapNotNull {
        InstanceUrlNormalizer.normalize(it).getOrNull()?.removePrefix("https://")
    }.distinct()

    private companion object {
        const val WEEK = 7 * 24 * 60 * 60 * 1000L
    }
}
