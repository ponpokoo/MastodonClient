package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RelayRegistrationProtocolTest {
    private val id = "r".repeat(32)
    private val secret = "s".repeat(43)
    private val json = Json { ignoreUnknownKeys = true }
    private fun client(server: MockWebServer) = RelayRegistrationDataSource(
        Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().followRedirects(false).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(RelayApi::class.java),
    )

    @Test fun tokenRotationUsesSameRegistrationAndOnlyRelayCredentials() = runTest {
        MockWebServer().use { server ->
            val client = client(server)
            for (token in listOf("first-fcm-token", "rotated-fcm-token")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"endpoint":"https://relay.example/push/opaque","future":true}"""))
                assertEquals("https://relay.example/push/opaque", client.put(id, secret, token).endpoint)
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/v1/registrations/$id", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals("""{"fcmToken":"$token"}""", request.body.readUtf8())
            }
        }
    }

    @Test fun authorizationFailureDoesNotLookLikeSuccessfulRemoval() = runTest {
        MockWebServer().use { server ->
            val client = client(server)
            server.enqueue(MockResponse().setResponseCode(404))
            client.remove(id, secret)
            server.enqueue(MockResponse().setResponseCode(401))
            try { client.remove(id, secret); fail("Removal was not authorized") }
            catch (error: HttpException) { assertEquals(401, error.code()) }
        }
    }

    @Test fun malformedRegistrationCannotChangeRequestPath() = runTest {
        MockWebServer().use { server ->
            try { client(server).put("../other", secret, "token"); fail("Invalid ID accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun productionConfigurationRejectsHttpAndEmbeddedCredentials() {
        for (url in listOf("http://relay.example/", "https://user:secret@relay.example/", "https://relay.example/?token=secret")) {
            try { RelayRegistrationDataSource(url); fail("Invalid configuration accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
