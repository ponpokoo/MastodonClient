package io.github.ponpokoo.mastodonclient.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientFactoryTest {
    @Test
    fun reusedClientsKeepAccountAuthorizationIsolated() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            val factory = ApiClientFactory()
            val url = server.url("/").toString()
            val first = factory.create(url, "account-a")
            org.junit.Assert.assertSame(first, factory.create(url.removeSuffix("/"), "account-a"))
            first.getInstance()
            factory.create(url, "account-b").getInstance()
            factory.create(url).getInstance()
            assertEquals("Bearer account-a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer account-b", server.takeRequest().getHeader("Authorization"))
            org.junit.Assert.assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun instanceResponseIgnoresUnknownFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "domain": "example.social",
                      "title": "Example",
                      "version": "4.4.0",
                      "unknown_future_field": true,
                      "configuration": {
                        "statuses": { "max_characters": 1000 },
                        "media_attachments": { "max_attachments": 8 }
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val api = ApiClientFactory().create(server.url("/").toString())
            val instance = api.getInstance()

            assertEquals("example.social", instance.domain)
            assertEquals(1000, instance.configuration?.statuses?.maxCharacters)
            assertEquals(8, instance.configuration?.mediaAttachments?.maxAttachments)
        }
    }
}
