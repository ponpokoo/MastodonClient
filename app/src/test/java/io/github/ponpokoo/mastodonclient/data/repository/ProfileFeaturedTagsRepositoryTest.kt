package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class ProfileFeaturedTagsRepositoryTest {
    private fun account(server: MockWebServer) = AccountSession("one", server.url("/").toString(), "me", "me", "Me", "", "test-token")

    @Test fun tagFilteringWaitsBeyondThirtySecondsWithoutExtendingUnfilteredProfiles() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(31, java.util.concurrent.TimeUnit.SECONDS).setBody("[]"))
            server.enqueue(MockResponse().setHeadersDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("[]"))
            val client = okhttp3.OkHttpClient.Builder().readTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val repository = DefaultTimelineRepository(ApiClientFactory(client))
            val session = account(server)
            assertTrue(repository.getTaggedProfileStatuses(session, "author", ProfileStatusTab.Posts, "写真").isSuccess)
            val failure = repository.getProfileStatuses(session, "author", ProfileStatusTab.Posts).exceptionOrNull()
            assertTrue(failure is java.net.SocketTimeoutException)
            assertEquals("写真", server.takeRequest().requestUrl!!.queryParameter("tagged"))
            assertNull(server.takeRequest().requestUrl!!.queryParameter("tagged"))
        }
    }

    @Test fun featuredTagsDecodeCountsWithoutRejectingMissingOrInvalidCounts() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"id":"opaque-id","name":"写真","statuses_count":12,"last_status_at":null,"future":true},
                {"id":"other","name":"NowPlaying","statuses_count":"24","last_status_at":"2026-10-18"},
                {"id":"duplicate","name":"nowplaying"}, {"id":"empty","name":""},
                {"id":"missing","name":"Missing"}, {"id":"null","name":"Null","statuses_count":null},
                {"id":"invalid","name":"Invalid","statuses_count":"unknown"},
                {"id":"negative","name":"Negative","statuses_count":-1},
                {"id":"object","name":"Object","statuses_count":{}},
                {"id":"zero","name":"Zero","statuses_count":"0"}
            ]"""))
            val result = DefaultTimelineRepository(ApiClientFactory()).getFeaturedTags(account(server), "remote-account").getOrThrow()
            assertEquals(listOf(FeaturedTag("opaque-id", "写真", 12), FeaturedTag("other", "NowPlaying", 24),
                FeaturedTag("missing", "Missing"), FeaturedTag("null", "Null"), FeaturedTag("invalid", "Invalid"),
                FeaturedTag("negative", "Negative"), FeaturedTag("object", "Object"), FeaturedTag("zero", "Zero", 0)), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/accounts/remote-account/featured_tags", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test fun unsupportedIsEmptyWhileAuthenticationAndTemporaryErrorsRemainRetryableFailures() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            for (code in listOf(404, 405, 501, 401, 403, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val result = repository.getFeaturedTags(account(server), "author")
                if (code in listOf(404, 405, 501)) assertEquals(emptyList<FeaturedTag>(), result.getOrThrow())
                else assertEquals(code, (result.exceptionOrNull() as HttpException).code())
            }
        }
    }

    @Test fun taggedPostsCarryTheFilterThroughTabsAndPaginationWithoutFetchingPins() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = account(server)
            for (tab in ProfileStatusTab.entries) {
                server.enqueue(MockResponse().setBody("""[{"id":"opaque-post","created_at":"2026-10-18T00:00:00Z","account":{"id":"author","username":"a","acct":"a"}}]"""))
                val result = repository.getTaggedProfileStatuses(session, "author", tab, "写真", "opaque-cursor").getOrThrow()
                assertEquals("opaque-post", result.statuses.single().statusId)
                assertNotNull(repository.getCachedStatus(session, "opaque-post"))
                assertTrue(result.endReached)
                val request = server.takeRequest()
                assertEquals("/api/v1/accounts/author/statuses", request.requestUrl!!.encodedPath)
                assertEquals("写真", request.requestUrl!!.queryParameter("tagged"))
                assertEquals("opaque-cursor", request.requestUrl!!.queryParameter("max_id"))
                assertEquals("true", request.requestUrl!!.queryParameter("exclude_reblogs"))
                assertEquals((tab == ProfileStatusTab.Posts).toString(), request.requestUrl!!.queryParameter("exclude_replies"))
                assertEquals((tab == ProfileStatusTab.Media).toString(), request.requestUrl!!.queryParameter("only_media"))
                assertEquals("false", request.requestUrl!!.queryParameter("pinned"))
            }
            assertEquals(3, server.requestCount)
        }
    }
}
