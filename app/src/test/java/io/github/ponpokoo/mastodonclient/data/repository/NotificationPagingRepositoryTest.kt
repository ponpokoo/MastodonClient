package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NotificationPagingRepositoryTest {
    private fun session(server: MockWebServer) = AccountSession("one", server.url("/").toString(), "me", "me", "Me", "", "unused")
    private fun notification(id: String, type: String) = """{"id":"$id","type":"$type","created_at":"2026-10-06T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"}}"""

    @Test fun filteredMentionAndReactionRequestsKeepTypeAndCursorAtFortyRows() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${notification("opaque-mention", "mention")}]"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[${notification("reaction", "emoji_reaction")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
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
            assertTrue(DefaultTimelineRepository(ApiClientFactory()).getNotificationCapabilities(session(server)).isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingCapabilitiesAndOldVersionUseBoundedLocalFiltering() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"3.4.6"}"""))
            server.enqueue(MockResponse().setBody("[${notification("favourite", "favourite")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            val capability = repository.getNotificationCapabilities(account).getOrThrow()
            assertFalse(capability.supportsEmojiReactions)
            assertFalse(capability.supportsTypeFiltering)
            val page = repository.getNotificationPage(account, NotificationCategory.Mentions, supportsTypeFiltering = capability.supportsTypeFiltering).getOrThrow()
            server.takeRequest()
            assertNull(server.takeRequest().requestUrl!!.queryParameter("types[]"))
            assertFalse(page.serverFiltered)
            assertFalse(page.endReached)
        }
    }
}
