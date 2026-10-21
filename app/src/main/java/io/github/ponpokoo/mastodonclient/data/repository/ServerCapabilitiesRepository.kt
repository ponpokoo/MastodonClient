package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCapabilities
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

/** Public server metadata shared by notifications, Push, history and status actions. */
class ServerCapabilitiesRepository(
    private val clients: ApiClientFactory,
    private val preferences: UserPreferencesStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 4_000,
) {
    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<String, NotificationCapabilities>>(emptyMap())

    fun cached(instanceUrl: String): NotificationCapabilities? = cache.value[instanceUrl.toHttpUrl().toString().trimEnd('/')]

    fun observe(instanceUrl: String) = instanceUrl.toHttpUrl().toString().trimEnd('/').let { key ->
        cache.map { it[key] ?: NotificationCapabilities() }.distinctUntilChanged()
    }

    private fun publish(key: String, capabilities: NotificationCapabilities) {
        cache.update { it + (key to capabilities) }
    }

    suspend fun rejectPushAlerts(instanceUrl: String) = mutex.withLock {
        val key = instanceUrl.toHttpUrl().toString().trimEnd('/')
        val current = cache.value[key] ?: return@withLock
        val updated = current.copy(pushAlerts = emptySet())
        runCatchingCancellable { preferences?.saveNotificationCapabilities(key, updated) }
        publish(key, updated)
    }

    suspend fun get(instanceUrl: String): NotificationCapabilities = mutex.withLock {
        val key = instanceUrl.toHttpUrl().toString().trimEnd('/')
        val now = clock()
        fun NotificationCapabilities.fresh(): Boolean = if (retryAtEpochMillis > 0) isRetryPending(now)
            else reactionApi != EmojiReactionApi.Unknown && checkedAtEpochMillis > 0 && now - checkedAtEpochMillis in 0 until TTL_MILLIS
        val memory = cache.value[key]
        memory?.takeIf { it.fresh() }?.let { return@withLock it }
        val stored = runCatchingCancellable { preferences?.preferences?.first()?.notificationCapabilities?.get(key) }.getOrNull()
        stored?.takeIf { it.fresh() }?.let { publish(key, it); return@withLock it }
        val previous = memory?.takeIf { it.reactionApi != EmojiReactionApi.Unknown }
            ?: stored?.takeIf { it.reactionApi != EmojiReactionApi.Unknown }
        val result = withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMillis) { discover(key, previous?.reactionApi) } }
            ?: NotificationCapabilities()
        currentCoroutineContext().ensureActive()
        if (result.reactionApi == EmojiReactionApi.Unknown) {
            // Preserve the last confirmed API, filters and Push decisions across failures.
            // Retry scheduling is memory-only; restart still revalidates the expired record.
            val fallback = (previous ?: result.copy(checkedAtEpochMillis = clock()))
                .copy(retryAtEpochMillis = clock() + RETRY_MILLIS)
            publish(key, fallback)
            return@withLock fallback
        }
        val checked = result.copy(checkedAtEpochMillis = clock())
        runCatchingCancellable { preferences?.saveNotificationCapabilities(key, checked) }
        publish(key, checked)
        checked
    }

    private suspend fun discover(url: String, previousApi: EmojiReactionApi?): NotificationCapabilities {
        val api = clients.create(url)
        val instance = runCatchingCancellable {
            try { api.getInstance() } catch (error: HttpException) {
                if (error.code() != 404) throw error
                api.getLegacyInstance()
            }
        }.getOrNull()
        // NodeInfo cannot disprove a previously confirmed Fedibird contract when
        // the Instance API itself failed. Keep it until that API can be checked.
        if (instance == null && previousApi == EmojiReactionApi.Fedibird) return NotificationCapabilities()
        val version = Regex("""^(\d+)\.(\d+)""").find(instance?.version.orEmpty())
        val major = version?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minor = version?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val typeFiltering = major > 3 || major == 3 && minor >= 5
        // A confirmed Fedibird contract needs no NodeInfo request.
        if ("emoji_reaction" in instance?.fedibirdCapabilities.orEmpty()) {
            return NotificationCapabilities(typeFiltering, true, EmojiReactionApi.Fedibird, true)
        }
        val node = runCatchingCancellable {
            val links = try { api.getNodeInfoDiscovery().links } catch (error: HttpException) {
                if (error.code() != 404) throw error
                emptyList()
            }
            val candidates = listOf("2.1", "2.0").mapNotNull { version ->
                links.firstOrNull { it.rel == "http://nodeinfo.diaspora.software/ns/schema/$version" }
            }
            var metadata: io.github.ponpokoo.mastodonclient.data.remote.dto.NodeInfoMetadataDto? = null
            for (link in candidates) {
                val href = requireNotNull(link.href.toHttpUrlOrNull())
                val origin = url.toHttpUrl()
                require(href.username.isEmpty() && href.password.isEmpty() && href.fragment == null &&
                    (href.isHttps || !origin.isHttps && href.host == origin.host && href.port == origin.port))
                try {
                    metadata = requireNotNull(api.getNodeInfo(href.toString()).metadata)
                    break
                } catch (error: HttpException) {
                    if (error.code() != 404 || link == candidates.last()) throw error
                }
            }
            metadata
        }
        val features = node.getOrNull()?.features.orEmpty()
        val pleroma = "pleroma_emoji_reactions" in features
        val includeTypes = "pleroma:api/v1/notifications:include_types_filter" in features
        return NotificationCapabilities(
            supportsTypeFiltering = if (pleroma) includeTypes else typeFiltering,
            supportsEmojiReactions = pleroma,
            reactionApi = when {
                pleroma -> EmojiReactionApi.Pleroma
                instance == null || node.isFailure -> EmojiReactionApi.Unknown
                else -> EmojiReactionApi.Unsupported
            },
            supportsCustomEmojiReactions = pleroma && "custom_emoji_reactions" in features,
            usesIncludeTypes = pleroma && includeTypes,
        )
    }

    companion object {
        const val TTL_MILLIS = NotificationCapabilities.TTL_MILLIS
        const val RETRY_MILLIS = NotificationCapabilities.RETRY_MILLIS
    }
}
