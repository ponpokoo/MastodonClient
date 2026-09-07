package io.github.ponpokoo.mastodonclient.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientFactoryTest {
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
