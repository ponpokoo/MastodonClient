package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExploreRepositoryTest {
    private fun session(server: MockWebServer) = AccountSession("one", server.url("/").toString(), "me", "me", "Me", "", "test-token")

    @Test fun trendsMapDailyCountsNewsImagesAndOpaqueStatuses() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"opaque-post","created_at":"2026-10-04T00:00:00Z","account":{"id":"author","username":"a","acct":"a"}}]"""))
            server.enqueue(MockResponse().setBody("""[{"name":"写真","url":"https://example.test/tags/photo","history":[{"day":"100","accounts":"900"},{"day":"200","accounts":"456"}]},{"name":"missing","url":"https://example.test/tags/missing"}]"""))
            server.enqueue(MockResponse().setBody("""[{"url":"https://news.test/one","title":"News","provider_name":"Publisher","image":"https://news.test/image.jpg","new_field":true},{"url":"https://news.test/two","image":null}]"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            val posts = repository.getExplore(account, ExploreFeed.Posts).getOrThrow()
            assertEquals("opaque-post", posts.statuses.single().statusId)
            assertNotNull(repository.getCachedStatus(account, "opaque-post"))
            val tags = repository.getExplore(account, ExploreFeed.Hashtags, "20").getOrThrow()
            assertEquals(listOf(456L, null), tags.tags.map { it.postingAccounts })
            assertEquals("22", tags.nextCursor)
            val news = repository.getExplore(account, ExploreFeed.News).getOrThrow()
            assertEquals("Publisher", news.news.first().provider)
            assertEquals("https://news.test/image.jpg", news.news.first().imageUrl)
            assertNull(news.news.last().imageUrl)
            val requests = List(3) { server.takeRequest() }
            assertEquals(listOf("/api/v1/trends/statuses", "/api/v1/trends/tags", "/api/v1/trends/links"), requests.map { it.requestUrl!!.encodedPath })
            assertTrue(requests.all { it.requestUrl!!.queryParameter("limit") == "20" && it.getHeader("Authorization") == "Bearer test-token" })
            assertEquals("20", requests[1].requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun followedPaginationUsesRelationshipCursorAndNeverFollowsHeaderHost() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Link", "<https://other.test/api/v1/followed_tags?max_id=relationship%2Fnext&limit=20>; rel=\"next\"")
                .setBody("""[{"id":"tag-id-is-not-cursor","name":"写真","url":"https://example.test/tags/photo"}]"""))
            server.enqueue(MockResponse().setBody("[]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            val first = repository.getExplore(account, ExploreFeed.Followed).getOrThrow()
            assertEquals("relationship/next", first.nextCursor)
            assertEquals(true, first.tags.single().following)
            val end = repository.getExplore(account, ExploreFeed.Followed, first.nextCursor).getOrThrow()
            assertNull(end.nextCursor)
            server.takeRequest()
            val next = server.takeRequest()
            assertEquals("/api/v1/followed_tags", next.requestUrl!!.encodedPath)
            assertEquals("relationship/next", next.requestUrl!!.queryParameter("max_id"))
        }
    }

    @Test fun subscriptionActionsUseEncodedNameAndDistinguishUnsupportedFromAuthErrors() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("""{"name":"写真","url":"https://example.test/tags/photo","following":false}""")) }
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(403))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val account = session(server)
            assertEquals(false, repository.getTag(account, "写真").getOrThrow().following)
            assertEquals(true, repository.setTagFollowing(account, "写真", true).getOrThrow().following)
            assertEquals(false, repository.setTagFollowing(account, "写真", false).getOrThrow().following)
            assertTrue(repository.getExplore(account, ExploreFeed.News).exceptionOrNull() is UnsupportedOperationException)
            assertEquals(SearchFailure.Authentication, (repository.getExplore(account, ExploreFeed.Followed).exceptionOrNull() as SearchException).failure)
            val requests = List(3) { server.takeRequest() }
            assertEquals(listOf("GET", "POST", "POST"), requests.map { it.method })
            assertEquals(listOf("/api/v1/tags/%E5%86%99%E7%9C%9F", "/api/v1/tags/%E5%86%99%E7%9C%9F/follow", "/api/v1/tags/%E5%86%99%E7%9C%9F/unfollow"), requests.map { it.requestUrl!!.encodedPath })
        }
    }
}
