package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCapabilities
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NotificationPagingRepositoryTest {
    private fun session(server: MockWebServer) = AccountSession("one", server.url("/").toString(), "me", "me", "Me", "", "unused")
    private fun notification(id: String, type: String) = """{"id":"$id","type":"$type","created_at":"2026-10-06T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"}}"""

    @Test fun confirmedCapabilitiesSurviveRestartAndRemainSeparatePerInstance() = runTest {
        val data = MemoryAuthPreferences()
        val preferences = UserPreferencesStore(data)
        preferences.setThemeMode(ThemeMode.Dark)
        MockWebServer().use { supported -> MockWebServer().use { unsupported ->
            supported.enqueue(MockResponse().setBody("""{"version":"4.0.0","fedibird_capabilities":["emoji_reaction"]}"""))
            unsupported.enqueue(MockResponse().setBody("""{"version":"3.4.6"}"""))
            unsupported.enqueue(MockResponse().setResponseCode(404))
            val first = DefaultTimelineRepository(ApiClientFactory(), settingsStore = preferences, settingsScope = backgroundScope)
            assertTrue(first.getNotificationCapabilities(session(supported)).getOrThrow().supportsEmojiReactions)
            assertEquals(EmojiReactionApi.Unsupported, first.getNotificationCapabilities(session(unsupported)).getOrThrow().reactionApi)
            // Extra network calls would receive an error instead of a queued capability response.
            supported.enqueue(MockResponse().setResponseCode(500))
            unsupported.enqueue(MockResponse().setResponseCode(500))
            val restoredStore = UserPreferencesStore(data)
            val restarted = DefaultTimelineRepository(ApiClientFactory(), settingsStore = restoredStore, settingsScope = backgroundScope)
            assertTrue(restarted.getNotificationCapabilities(
                session(supported).copy(sessionId = "other-account", accountId = "other", instanceUrl = session(supported).instanceUrl.trimEnd('/'))).getOrThrow().supportsEmojiReactions)
            assertEquals(EmojiReactionApi.Unsupported, restarted.getNotificationCapabilities(session(unsupported)).getOrThrow().reactionApi)
            assertEquals(1, supported.requestCount)
            assertEquals(2, unsupported.requestCount)
            restoredStore.setOpenLinksInApp(false)
            restoredStore.removeAccountData("one")
            val restored = UserPreferencesStore(data).preferences.first()
            assertEquals(ThemeMode.Dark, restored.themeMode)
            assertFalse(restored.openLinksInApp)
            assertEquals(2, restored.notificationCapabilities.size)
        } }
    }

    @Test fun failedCapabilityChecksAreNotPersistedAndNextStartupCanRecover() = runTest {
        val data = MemoryAuthPreferences()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(503))
            val first = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(data), settingsScope = backgroundScope)
            assertEquals(EmojiReactionApi.Unknown, first.getNotificationCapabilities(session(server)).getOrThrow().reactionApi)
            assertTrue(UserPreferencesStore(data).preferences.first().notificationCapabilities.isEmpty())
            server.enqueue(MockResponse().setBody("""{"version":"4.0.0","fedibird_capabilities":["emoji_reaction"]}"""))
            val restarted = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(data), settingsScope = backgroundScope)
            assertTrue(restarted.getNotificationCapabilities(session(server)).getOrThrow().supportsEmojiReactions)
            server.enqueue(MockResponse().setResponseCode(500))
            val next = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(data), settingsScope = backgroundScope)
            assertTrue(next.getNotificationCapabilities(session(server)).getOrThrow().supportsEmojiReactions)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun concurrentInitialChecksForSameInstanceShareOneRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"4.0.0","fedibird_capabilities":["emoji_reaction"]}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(MemoryAuthPreferences()), settingsScope = backgroundScope)
            val results = coroutineScope {
                listOf(async { repository.getNotificationCapabilities(session(server)) },
                    async { repository.getNotificationCapabilities(session(server).copy(sessionId = "second")) }).awaitAll()
            }
            assertTrue(results.all { it.getOrThrow().supportsEmojiReactions })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancelledCapabilityRequestDoesNotPersistAResultAndCanRecoverAfterRestart() = runTest {
        val data = MemoryAuthPreferences()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repository = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(data), settingsScope = backgroundScope)
            val pending = async { repository.getNotificationCapabilities(session(server)) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(UserPreferencesStore(data).preferences.first().notificationCapabilities.isEmpty())
            server.enqueue(MockResponse().setBody("""{"version":"4.0.0","fedibird_capabilities":["emoji_reaction"]}"""))
            val restarted = DefaultTimelineRepository(ApiClientFactory(), settingsStore = UserPreferencesStore(data), settingsScope = backgroundScope)
            assertTrue(restarted.getNotificationCapabilities(session(server)).getOrThrow().supportsEmojiReactions)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun filteredMentionAndReactionRequestsKeepTypeAndCursorAtFortyRows() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"4.0.0","fedibird_capabilities":["emoji_reaction"]}"""))
            server.enqueue(MockResponse().setBody("[${notification("opaque-mention", "mention")}]"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[${notification("reaction", "emoji_reaction")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            repository.getNotificationCapabilities(account).getOrThrow()
            server.takeRequest()
            val first = repository.getNotificationPage(account, NotificationCategory.Mentions, supportsTypeFiltering = true).getOrThrow()
            repository.getNotificationPage(account, NotificationCategory.Mentions, first.nextMaxId, supportsTypeFiltering = true).getOrThrow()
            repository.getNotificationPage(account, NotificationCategory.Reactions, supportsTypeFiltering = true).getOrThrow()
            val initial = server.takeRequest().requestUrl!!
            assertEquals("40", initial.queryParameter("limit"))
            assertEquals("mention", initial.queryParameter("types[]"))
            val next = server.takeRequest().requestUrl!!
            assertEquals("opaque-mention", next.queryParameter("max_id"))
            assertEquals("mention", next.queryParameter("types[]"))
            assertEquals("emoji_reaction", server.takeRequest().requestUrl!!.queryParameter("types[]"))
            assertTrue(first.serverFiltered)
        }
    }

    @Test fun ignoredFilterKeepsRawCursorAndDoesNotTreatEmptyVisiblePageAsHistoryEnd() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${notification("favourite-only", "favourite")}]"))
            val page = DefaultTimelineRepository(ApiClientFactory())
                .getNotificationPage(session(server), NotificationCategory.Mentions, supportsTypeFiltering = true).getOrThrow()
            assertTrue(page.notifications.isEmpty())
            assertEquals("favourite-only", page.nextMaxId)
            assertFalse(page.endReached)
            assertFalse(page.serverFiltered)
        }
    }

    @Test fun rejectedFilterFallsBackButAuthenticationAndServerErrorsDoNot() = runTest {
        for (code in listOf(400, 401, 403, 429, 500)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
            if (code == 400) server.enqueue(MockResponse().setBody("[${notification("old", "mention")}]"))
            val result = DefaultTimelineRepository(ApiClientFactory())
                .getNotificationPage(session(server), NotificationCategory.Mentions, supportsTypeFiltering = true)
            assertEquals(code == 400, result.isSuccess)
            assertEquals(if (code == 400) 2 else 1, server.requestCount)
            server.takeRequest()
            if (code == 400) {
                assertNull(server.takeRequest().requestUrl!!.queryParameter("types[]"))
                assertFalse(result.getOrThrow().serverFiltered)
            }
        }
    }

    @Test fun legacyInstanceCapabilityIsUsedOnlyOnNotFoundAndWithoutAuthentication() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"version":"4.0.0+fork","fedibird_capabilities":["emoji_reaction"],"future_field":true}"""))
            val capability = DefaultTimelineRepository(ApiClientFactory()).getNotificationCapabilities(session(server)).getOrThrow()
            assertTrue(capability.supportsEmojiReactions)
            assertTrue(capability.supportsTypeFiltering)
            assertEquals("/api/v2/instance", server.takeRequest().path)
            val legacy = server.takeRequest()
            assertEquals("/api/v1/instance", legacy.path)
            assertNull(legacy.getHeader("Authorization"))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(EmojiReactionApi.Unknown, DefaultTimelineRepository(ApiClientFactory()).getNotificationCapabilities(session(server)).getOrThrow().reactionApi)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun missingCapabilitiesAndOldVersionUseBoundedLocalFiltering() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"3.4.6"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("[${notification("favourite", "favourite")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            val capability = repository.getNotificationCapabilities(account).getOrThrow()
            assertFalse(capability.supportsEmojiReactions)
            assertFalse(capability.supportsTypeFiltering)
            val page = repository.getNotificationPage(account, NotificationCategory.Mentions, supportsTypeFiltering = capability.supportsTypeFiltering).getOrThrow()
            server.takeRequest()
            server.takeRequest()
            assertNull(server.takeRequest().requestUrl!!.queryParameter("types[]"))
            assertFalse(page.serverFiltered)
            assertFalse(page.endReached)
        }
    }
}
