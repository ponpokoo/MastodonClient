package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.SearchException
import io.github.ponpokoo.mastodonclient.domain.model.SearchFailure
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SearchRepositoryTest {
    @Test fun selectedTypeOffsetAndDailyAccountsComeFromSelectedServer() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"accounts":[{"id":"opaque-account","username":"a","acct":"a"}]}"""))
            server.enqueue(MockResponse().setBody("""{"hashtags":[
                {"name":"写真","url":"https://example.test/tags/photo","history":[
                    {"day":"200","accounts":"456","uses":"900"},{"day":"100","accounts":"411"}]},
                {"name":"履歴なし","url":"https://example.test/tags/no-history"},
                {"name":"不正な人数","url":"https://example.test/tags/invalid","history":[{"day":"200","accounts":"invalid"}]}
            ],"future_field":true}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "test-token")
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val accounts = repository.search(session, " hello ", SearchTarget.Accounts, offset = 20).getOrThrow()
            assertEquals("opaque-account", accounts.results.accounts.single().id)
            assertEquals(21, accounts.nextOffset)
            assertFalse(accounts.endReached)
            val tags = repository.search(session, "写真", SearchTarget.Hashtags).getOrThrow()
            assertEquals(listOf(456L, null, null), tags.results.hashtags.map { it.postingAccounts })
            assertEquals(3, tags.nextOffset)
            val end = repository.search(session, "写真", SearchTarget.Hashtags, offset = 3).getOrThrow()
            assertTrue(end.endReached)
            assertNull(end.nextOffset)
            val first = server.takeRequest()
            assertEquals("accounts", first.requestUrl?.queryParameter("type"))
            assertEquals("hello", first.requestUrl?.queryParameter("q"))
            assertEquals("20", first.requestUrl?.queryParameter("offset"))
            assertEquals("20", first.requestUrl?.queryParameter("limit"))
            assertEquals("false", first.requestUrl?.queryParameter("resolve"))
            assertEquals("Bearer test-token", first.getHeader("Authorization"))
            assertEquals("hashtags", server.takeRequest().requestUrl?.queryParameter("type"))
            assertEquals("3", server.takeRequest().requestUrl?.queryParameter("offset"))
        }
    }

    @Test fun httpFailuresAreClassifiedWithoutExposingServerMessage() = runTest {
        MockWebServer().use { server ->
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "test-token")
            val repository = DefaultTimelineRepository(ApiClientFactory())
            for ((code, expected) in listOf(401 to SearchFailure.Authentication, 403 to SearchFailure.Authentication,
                429 to SearchFailure.RateLimited, 503 to SearchFailure.Server, 422 to SearchFailure.Rejected)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"private server details"}"""))
                assertEquals(expected, (repository.search(session, "hello").exceptionOrNull() as SearchException).failure)
            }
        }
    }

    @Test fun timeoutAndConnectionFailuresRemainDistinct() = runTest {
        for ((failure, expected) in listOf(java.net.SocketTimeoutException("private") to SearchFailure.Timeout,
            java.io.InterruptedIOException("timeout") to SearchFailure.Timeout, java.io.IOException("private") to SearchFailure.Connection)) {
            val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
            val session = AccountSession("session", "https://example.test", "me", "me", "Me", "", "test-token")
            val error = DefaultTimelineRepository(ApiClientFactory(client)).search(session, "hello").exceptionOrNull()
            assertEquals(expected, (error as SearchException).failure)
        }
    }
}
