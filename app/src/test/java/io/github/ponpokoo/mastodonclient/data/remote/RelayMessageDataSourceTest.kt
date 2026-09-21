package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RelayMessageDataSourceTest {
    private val id = "r".repeat(43)
    private val message = "m".repeat(43)
    private val token = "s".repeat(43)
    private fun client() = RelayMessageDataSource(OkHttpClient.Builder().followRedirects(false).build(), true)
    @Test fun fetchUsesManagementCredentialsAndFixedResourcePath() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"version":"1","registrationId":"$id","messageId":"$message","encoding":"aes128gcm","headers":"{}","body":"AAAA","future":true}"""))
            val result = client().fetch(server.url("/").toString(), id, message, token)
            assertEquals("AAAA", result!!.body)
            val request = server.takeRequest()
            assertEquals("/v1/registrations/$id/messages/$message", request.path)
            assertEquals("Bearer $token", request.getHeader("Authorization"))
        }
    }
    @Test fun expiredIsDistinctFromServerFailureAndRedirectIsNotFollowed() = runTest {
        MockWebServer().use { server -> MockWebServer().use { attacker ->
            val client = client()
            server.enqueue(MockResponse().setResponseCode(410))
            assertNull(client.fetch(server.url("/").toString(), id, message, token))
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setResponseCode(302).setHeader("Location", attacker.url("/")))) {
                server.enqueue(response)
                try { client.fetch(server.url("/").toString(), id, message, token); fail() } catch (_: IOException) { }
            }
            assertEquals(0, attacker.requestCount)
        } }
    }
    @Test fun oversizedChunkedResponseIsBounded() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(100001), 4096))
            try { client().fetch(server.url("/").toString(), id, message, token); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun productionClientRejectsHttpBeforeSendingSecrets() = runTest {
        MockWebServer().use { server ->
            try { RelayMessageDataSource().fetch(server.url("/").toString(), id, message, token); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
