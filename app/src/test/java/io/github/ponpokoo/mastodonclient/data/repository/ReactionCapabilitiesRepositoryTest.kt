package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionCapabilitiesRepositoryTest {
    @Test fun notificationsHistoryActionsAndAccountsShareExpiryBackoffAndLiveInvalidation() = runTest {
        MockWebServer().use { server ->
            // 0: supported, 1: failed recheck, 2: successfully confirmed unsupported.
            val mode = AtomicInteger(0)
            val metadataRequests = AtomicInteger()
            val operationRequests = AtomicInteger()
            val status = """{"id":"post","created_at":"now","account":{"id":"me","username":"me","acct":"me"}}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/v2/instance" -> {
                        metadataRequests.incrementAndGet()
                        when (mode.get()) {
                            0 -> MockResponse().setBody("""{"version":"4.5","fedibird_capabilities":["emoji_reaction"]}""")
                            1 -> MockResponse().setResponseCode(503)
                            else -> MockResponse().setBody("""{"version":"4.5"}""")
                        }
                    }
                    request.path == "/.well-known/nodeinfo" -> MockResponse().setResponseCode(404)
                    else -> {
                        operationRequests.incrementAndGet()
                        MockResponse().setBody(if (request.method == "PUT" || request.method == "POST") status else "[]")
                    }
                }
            }
            var now = 1_000L
            val settings = UserPreferencesStore(MemoryAuthPreferences())
            val clients = ApiClientFactory()
            val shared = ServerCapabilitiesRepository(clients, settings, { now })
            val repository = DefaultTimelineRepository(clients, capabilitiesRepository = shared)
            val account = AccountSession("one", server.url("/").toString().trimEnd('/'), "me", "me", "Me", "", "unused")
            val observed = mutableListOf<ServerCapabilities>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeServerCapabilities(account).collect { observed += it }
            }
            val confirmed = repository.getServerCapabilities(account).getOrThrow()
            repository.getNotificationPage(account, NotificationCategory.Reactions).getOrThrow()
            repository.getSavedTimeline(account, SavedTimelineKind.Reactions).getOrThrow()
            repository.setFedibirdReaction(account, "post", "👍").getOrThrow()
            repository.getEmojiReactionedBy(account, "post", "👍").getOrThrow()
            now += ServerCapabilitiesRepository.TTL_MILLIS - 1
            repository.setFedibirdReaction(account.copy(sessionId = "two"), "post", null).getOrThrow()
            assertEquals(1, metadataRequests.get())

            now++
            mode.set(1)
            repository.setFedibirdReaction(account, "post", "👍").getOrThrow()
            val fallback = observed.last()
            assertTrue(fallback.supportsReactionActions)
            assertTrue(fallback.supportsReactionHistory)
            assertEquals(confirmed.checkedAtEpochMillis, fallback.checkedAtEpochMillis)
            assertEquals(confirmed, settings.preferences.first().notificationCapabilities[account.instanceUrl])
            now += ServerCapabilitiesRepository.RETRY_MILLIS - 1
            repository.getServerCapabilities(account).getOrThrow()
            assertEquals(2, metadataRequests.get())

            now++
            mode.set(2)
            val unsupported = repository.getServerCapabilities(account).getOrThrow()
            val previousOperationCount = operationRequests.get()
            assertTrue(repository.setFedibirdReaction(account, "post", null).isFailure)
            assertTrue(repository.getEmojiReactionedBy(account, "post", "👍").isFailure)
            assertTrue(repository.getSavedTimeline(account, SavedTimelineKind.Reactions).isFailure)
            assertFalse(unsupported.supportsEmojiReactions)
            assertFalse(unsupported.supportsReactionActions)
            assertFalse(unsupported.supportsReactionAccounts)
            assertFalse(unsupported.supportsReactionHistory)
            assertEquals(unsupported, observed.last())
            assertEquals(3, metadataRequests.get())
            assertEquals(previousOperationCount, operationRequests.get())
        }
    }
}
