package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ServerCapabilitiesRepositoryTest {
    @Test fun failedFedibirdRefreshKeepsConfirmationAndPushUntilSuccessfulDisablementCheck() = runTest {
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            var now = 1_000L
            val clients = ApiClientFactory()
            val caps = ServerCapabilitiesRepository(clients, settings, { now })
            val url = session(server).instanceUrl
            server.enqueue(MockResponse().setBody("""{"version":"4.5","fedibird_capabilities":["emoji_reaction"]}"""))
            val confirmed = caps.get(url)
            now += ServerCapabilitiesRepository.TTL_MILLIS
            server.enqueue(MockResponse().setResponseCode(503))
            val fallback = caps.get(url)
            assertEquals(confirmed, fallback.copy(retryAtEpochMillis = 0))
            assertEquals(confirmed, settings.preferences.first().notificationCapabilities[url.trimEnd('/')])
            val subscriptions = DefaultPushSubscriptionRepository(clients, caps)
            assertEquals(setOf("emoji_reaction"), subscriptions.additionalAlerts(session(server)))
            assertEquals(mapOf("poll" to true, "follow_request" to true), subscriptions.subscriptionAlerts(session(server),
                mapOf("poll" to true, "follow_request" to true)))
            assertEquals(2, server.requestCount) // A failed Instance check must not probe NodeInfo or change API families.
            now += ServerCapabilitiesRepository.RETRY_MILLIS - 1
            assertTrue(caps.get(url).supportsEmojiReactions)
            assertEquals(2, server.requestCount)
            now++
            server.enqueue(MockResponse().setBody("""{"version":"4.5"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val disabled = caps.get(url)
            assertEquals(EmojiReactionApi.Unsupported, disabled.reactionApi)
            assertFalse(disabled.supportsEmojiReactions)
            assertTrue(subscriptions.additionalAlerts(session(server)).isEmpty())
            assertEquals(disabled, settings.preferences.first().notificationCapabilities[url.trimEnd('/')])
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun restartedPleromaRetainsFiltersCustomEmojiAndRejectedPushDecisionOnNodeInfoFailure() = runTest {
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            val confirmed = NotificationCapabilities(true, true, EmojiReactionApi.Pleroma,
                supportsCustomEmojiReactions = true, usesIncludeTypes = true, checkedAtEpochMillis = 1_000,
                pushAlerts = emptySet())
            val url = session(server).instanceUrl
            settings.saveNotificationCapabilities(url, confirmed)
            var now = 1_000L + ServerCapabilitiesRepository.TTL_MILLIS
            val caps = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now })
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(discovery(server))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(confirmed, caps.get(url).copy(retryAtEpochMillis = 0))
            assertEquals(confirmed, settings.preferences.first().notificationCapabilities[url.trimEnd('/')])
            now += ServerCapabilitiesRepository.RETRY_MILLIS
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(discovery(server))
            server.enqueue(features())
            assertEquals(EmojiReactionApi.Unsupported, caps.get(url).reactionApi)
        }
    }

    @Test fun timeoutKeepsExpiredDurableConfirmationAndRestartRetriesWithoutPersistedBackoff() = runTest {
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            val confirmed = NotificationCapabilities(true, true, checkedAtEpochMillis = 1_000)
            val url = session(server).instanceUrl
            settings.saveNotificationCapabilities(url, confirmed)
            val now = 1_000L + ServerCapabilitiesRepository.TTL_MILLIS
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val timedOut = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now }, timeoutMillis = 150).get(url)
            assertEquals(confirmed, timedOut.copy(retryAtEpochMillis = 0))
            assertTrue(timedOut.isRetryPending(now))
            assertEquals(confirmed, settings.preferences.first().notificationCapabilities[url.trimEnd('/')])
            server.enqueue(MockResponse().setBody("""{"version":"4.5","fedibird_capabilities":["emoji_reaction"]}"""))
            val recovered = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now }).get(url)
            assertTrue(recovered.supportsEmojiReactions)
            assertEquals(now, recovered.checkedAtEpochMillis)
            assertEquals(0L, recovered.retryAtEpochMillis)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun staleConfirmationIsNeverSharedWithAnotherServer() = runTest {
        MockWebServer().use { first -> MockWebServer().use { second ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            settings.saveNotificationCapabilities(session(first).instanceUrl, NotificationCapabilities(true, true, checkedAtEpochMillis = 1_000))
            val caps = ServerCapabilitiesRepository(ApiClientFactory(), settings, { 1_000L + ServerCapabilitiesRepository.TTL_MILLIS })
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody("{}"))
            second.enqueue(MockResponse().setResponseCode(503))
            assertTrue(caps.get(session(first).instanceUrl).supportsEmojiReactions)
            assertEquals(EmojiReactionApi.Unknown, caps.get(session(second).instanceUrl).reactionApi)
            assertTrue(caps.get(session(first).instanceUrl.trimEnd('/')).supportsEmojiReactions)
            assertEquals(1, first.requestCount)
            assertEquals(2, second.requestCount)
        } }
    }

    @Test fun nodeInfo20FallbackAndServerSwitchKeepApiFamiliesSeparate() = runTest {
        MockWebServer().use { pleroma -> MockWebServer().use { fedibird ->
            val repo = ServerCapabilitiesRepository(ApiClientFactory())
            fedibird.enqueue(MockResponse().setBody("""{"fedibird_capabilities":["emoji_reaction"]}"""))
            pleroma.enqueue(MockResponse().setBody("{}"))
            pleroma.enqueue(discovery(pleroma))
            pleroma.enqueue(MockResponse().setResponseCode(404))
            pleroma.enqueue(features("pleroma_emoji_reactions"))
            assertEquals(EmojiReactionApi.Fedibird, repo.get(session(fedibird).instanceUrl).reactionApi)
            assertEquals(EmojiReactionApi.Pleroma, repo.get(session(pleroma).instanceUrl).reactionApi)
            assertEquals(EmojiReactionApi.Fedibird, repo.get(session(fedibird).copy(sessionId = "other").instanceUrl).reactionApi)
            assertEquals(1, fedibird.requestCount)
            assertEquals(listOf("/api/v2/instance", "/.well-known/nodeinfo", "/info/2.1", "/info/2.0"),
                List(4) { pleroma.takeRequest().path })
        } }
    }

    @Test fun discoveredNodeInfoFailureIsUnknownRatherThanUnsupported() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(discovery(server))
            server.enqueue(MockResponse().setResponseCode(503))
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            val result = ServerCapabilitiesRepository(ApiClientFactory(), settings).get(session(server).instanceUrl)
            assertEquals(EmojiReactionApi.Unknown, result.reactionApi)
            assertTrue(settings.preferences.first().notificationCapabilities.isEmpty())
        }
    }
    private fun session(server: MockWebServer) = AccountSession("one", server.url("/").toString(), "me", "me", "Me", "", "secret")
    private fun discovery(server: MockWebServer) = MockResponse().setBody("""{"links":[
        {"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"${server.url("/info/2.0")}"},
        {"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"${server.url("/info/2.1")}"}]}""")
    private fun features(vararg flags: String) = MockResponse().setBody(
        """{"metadata":{"features":[${flags.joinToString(",") { "\"$it\"" }}]},"future":true}""")

    @Test fun nodeInfoSelects21AndSeparatesNotificationFilteringCustomEmojiAndPush() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"version":"2.7.2 (compatible; anything)"}"""))
            server.enqueue(discovery(server))
            server.enqueue(features("pleroma_emoji_reactions", "custom_emoji_reactions", "pleroma:api/v1/notifications:include_types_filter"))
            val caps = ServerCapabilitiesRepository(ApiClientFactory()).get(server.url("/").toString())
            assertEquals(EmojiReactionApi.Pleroma, caps.reactionApi)
            assertTrue(caps.supportsCustomEmojiReactions)
            assertTrue(caps.supportsTypeFiltering)
            assertTrue(caps.usesIncludeTypes)
            assertEquals(setOf("pleroma:emoji_reaction"), caps.additionalPushAlerts)
            assertEquals(listOf("/api/v2/instance", "/api/v1/instance", "/.well-known/nodeinfo", "/info/2.1"),
                List(4) { server.takeRequest().also { assertNull(it.getHeader("Authorization")) }.path })
        }
    }

    @Test fun expiryRechecksDisabledFeatureAndOldBooleanCacheIsMigratedByRevalidation() = runTest {
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            val url = server.url("/").toString()
            settings.saveNotificationCapabilities(url, NotificationCapabilities(true, true))
            var now = 1_000L
            val caps = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now })
            server.enqueue(MockResponse().setBody("""{"version":"4.5","fedibird_capabilities":["emoji_reaction"]}"""))
            assertEquals(EmojiReactionApi.Fedibird, caps.get(url).reactionApi)
            now += ServerCapabilitiesRepository.TTL_MILLIS - 1
            assertTrue(caps.get(url).supportsEmojiReactions)
            assertEquals(1, server.requestCount)
            now++
            server.enqueue(MockResponse().setBody("""{"version":"4.5"}"""))
            server.enqueue(discovery(server))
            server.enqueue(features())
            assertEquals(EmojiReactionApi.Unsupported, caps.get(url).reactionApi)
            assertEquals(4, server.requestCount)
            val restarted = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now })
            assertFalse(restarted.get(url).supportsEmojiReactions)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun nodeInfoFailuresAreUnknownAndMemoryBackoffAllowsRecoveryWithoutPersistingFailure() = runTest {
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            var now = 1_000L
            val caps = ServerCapabilitiesRepository(ApiClientFactory(), settings, { now })
            val url = server.url("/").toString()
            server.enqueue(MockResponse().setBody("""{"version":"4.5"}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(EmojiReactionApi.Unknown, caps.get(url).reactionApi)
            assertEquals(EmojiReactionApi.Unknown, caps.get(url).reactionApi)
            assertTrue(settings.preferences.first().notificationCapabilities.isEmpty())
            assertEquals(2, server.requestCount)
            now += ServerCapabilitiesRepository.RETRY_MILLIS
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(discovery(server))
            server.enqueue(features("pleroma_emoji_reactions"))
            val recovered = caps.get(url)
            assertEquals(EmojiReactionApi.Pleroma, recovered.reactionApi)
            assertFalse(recovered.supportsCustomEmojiReactions)
            assertFalse(recovered.supportsTypeFiltering)
        }
    }

    @Test fun instanceFailureCanStillDiscoverPleromaAndTimeoutNeverPersistsUnsupported() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(discovery(server))
            server.enqueue(features("pleroma_emoji_reactions"))
            assertEquals(EmojiReactionApi.Pleroma, ServerCapabilitiesRepository(ApiClientFactory()).get(server.url("/").toString()).reactionApi)
        }
        MockWebServer().use { server ->
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val caps = ServerCapabilitiesRepository(ApiClientFactory(), settings, timeoutMillis = 150)
            assertEquals(EmojiReactionApi.Unknown, caps.get(server.url("/").toString()).reactionApi)
            assertTrue(settings.preferences.first().notificationCapabilities.isEmpty())
        }
    }

    @Test fun pleromaFilteringUsesIncludeTypesAndRejectedFilterKeepsRawPagingCursor() = runTest {
        MockWebServer().use { server ->
            val clients = ApiClientFactory()
            val caps = ServerCapabilitiesRepository(clients)
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(discovery(server))
            server.enqueue(features("pleroma_emoji_reactions", "pleroma:api/v1/notifications:include_types_filter"))
            val repo = DefaultTimelineRepository(clients, capabilitiesRepository = caps)
            repo.getServerCapabilities(session(server)).getOrThrow()
            repeat(3) { server.takeRequest() }
            server.enqueue(MockResponse().setBody("""[{"id":"opaque","type":"pleroma:emoji_reaction","emoji":"☕","created_at":"now","account":{"id":"actor","username":"actor","acct":"actor"}}]"""))
            val first = repo.getNotificationPage(session(server), NotificationCategory.Reactions, supportsTypeFiltering = true).getOrThrow()
            assertEquals("☕", first.notifications.single().matchingReaction?.name)
            assertEquals("pleroma:emoji_reaction", server.takeRequest().requestUrl!!.queryParameter("include_types[]"))
            server.enqueue(MockResponse().setResponseCode(422))
            server.enqueue(MockResponse().setBody("""[{"id":"raw-tail","type":"favourite","created_at":"now","account":{"id":"actor","username":"actor","acct":"actor"}}]"""))
            val next = repo.getNotificationPage(session(server), NotificationCategory.Reactions, first.nextMaxId, supportsTypeFiltering = true).getOrThrow()
            assertTrue(next.notifications.isEmpty())
            assertEquals("raw-tail", next.nextMaxId)
            assertFalse(next.endReached)
            assertFalse(next.serverFiltered)
            assertEquals("opaque", server.takeRequest().requestUrl!!.queryParameter("max_id"))
            assertNull(server.takeRequest().requestUrl!!.queryParameter("include_types[]"))
        }
    }

    @Test fun unknownReactionSupportDoesNotScanNotificationHistory() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            val page = DefaultTimelineRepository(ApiClientFactory()).getNotificationPage(session(server), NotificationCategory.Reactions).getOrThrow()
            assertTrue(page.notifications.isEmpty())
            assertTrue(page.endReached)
            assertEquals(2, server.requestCount)
        }
    }
}
