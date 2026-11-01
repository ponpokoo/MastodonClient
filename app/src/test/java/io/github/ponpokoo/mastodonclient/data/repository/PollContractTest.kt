package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.dto.PollConfigurationDto
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class PollContractTest {
    @Test fun oldInstanceFallbackReadsLimitsAndMissingOrInvalidValuesRemainUsable() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"configuration":{"polls":{"max_options":8,"max_characters_per_option":100,"min_expiration":60,"max_expiration":3600}}}"""))
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "token")
            val limits = DefaultTimelineRepository(ApiClientFactory()).getComposerConfiguration(session).getOrThrow().polls
            assertEquals(PollConfiguration(8, 100, 60, 3600, true), limits)
            assertEquals("/api/v2/instance", server.takeRequest().path)
            assertEquals("/api/v1/instance", server.takeRequest().path)
            assertEquals(PollConfiguration(), (null as PollConfigurationDto?).toDomain())
            val partial = PollConfigurationDto(maxExpiration = 60).toDomain()
            assertTrue(partial.minExpiration <= partial.maxExpiration)
            assertEquals(60L, partial.maxExpiration)
            assertFalse(partial.confirmed)
            val invalid = PollConfigurationDto(-1, 0, 500, 20).toDomain()
            assertEquals(4, invalid.maxOptions)
            assertTrue(invalid.minExpiration <= invalid.maxExpiration)
            assertFalse(invalid.confirmed)
        }
    }

    @Test fun creationSendsHiddenResultsAndMapsPollSpecificEmojiAndNullResults() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"status","created_at":"2026-11-01T00:00:00Z","account":{"id":"me","username":"me","acct":"me"},"poll":{"id":"opaque-poll","multiple":true,"options":[{"title":":fruit:","votes_count":null},{"title":"orange","votes_count":null}],"emojis":[{"shortcode":"fruit","url":"https://example.net/fruit.png","static_url":"https://example.net/fruit.png"}]}}"""))
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "token")
            val result = DefaultTimelineRepository(ApiClientFactory()).createStatus(session,
                CreateStatusRequest("question", pollOptions = listOf(":fruit:", "orange"), pollExpiresInSeconds = 3600,
                    pollMultiple = true, pollHideTotals = true), "key").getOrThrow()
            val request = server.takeRequest()
            val body = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertEquals("/api/v1/statuses", request.path)
            assertTrue(body.contains("poll[hide_totals]=true"))
            assertTrue(body.contains("poll[expires_in]=3600"))
            assertTrue(body.contains("poll[options][]=:fruit:"))
            assertEquals("https://example.net/fruit.png", result.poll!!.customEmojis["fruit"])
            assertNull(result.poll!!.options.first().votesCount)
        }
    }
}
