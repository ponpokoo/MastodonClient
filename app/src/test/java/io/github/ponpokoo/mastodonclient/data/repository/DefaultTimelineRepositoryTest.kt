package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultTimelineRepositoryTest {
    @Test
    fun loadsAuthenticatedPageAndMapsBoostedStatus() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "id": "outer-20",
                        "created_at": "2026-09-08T00:00:00.000Z",
                        "account": {
                          "id": "booster-1",
                          "username": "booster",
                          "acct": "booster@example.social",
                          "display_name": "Booster"
                        },
                        "reblog": {
                          "id": "status-10",
                          "created_at": "2026-09-07T23:00:00.000Z",
                          "account": {
                            "id": "author-1",
                            "username": "author",
                            "acct": "author@example.social",
                            "display_name": "Author",
                            "avatar": "https://cdn.example/avatar.png"
                          },
                          "content": "<p>Hello <strong>Mastodon</strong></p>",
                          "replies_count": 2,
                          "reblogs_count": 3,
                          "favourites_count": 5,
                          "media_attachments": [
                            {
                              "id": "media-1",
                              "type": "image",
                              "url": "https://cdn.example/image.png",
                              "preview_url": "https://cdn.example/preview.png",
                              "description": "ALT text"
                            }
                          ]
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
            )
            val session = AccountSession(
                sessionId = "session",
                instanceUrl = server.url("/").toString().removeSuffix("/"),
                accountId = "me",
                username = "me",
                displayName = "Me",
                avatarUrl = "",
                accessToken = "secret-token",
            )

            val page = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(session, maxId = "cursor-30", limit = 1)
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertEquals("/api/v1/timelines/home?max_id=cursor-30&limit=1", request.path)
            assertEquals("outer-20", page.nextMaxId)
            assertFalse(page.endReached)
            assertEquals("status-10", page.statuses.single().statusId)
            assertEquals("Author", page.statuses.single().author.displayName)
            assertEquals("Booster", page.statuses.single().boostedBy?.displayName)
            assertEquals("ALT text", page.statuses.single().mediaAttachments.single().description)
        }
    }
}
