package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultTimelineRepositoryTest {
    @Test
    fun loadsHashtagTimelineWithTagEndpoint() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("tagged")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())

            val page = repository.getHashtagTimeline(
                session = testSession(server),
                hashtag = "Android",
                maxId = "cursor",
                limit = 1,
            ).getOrThrow()

            assertEquals("tagged", page.statuses.single().statusId)
            assertEquals(
                "/api/v1/timelines/tag/Android?max_id=cursor&limit=1",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun loadsLocalAndFederatedTimelinesWithPublicEndpoint() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("local")}]"))
            server.enqueue(MockResponse().setBody("[${basicStatusJson("federated")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            repository.getTimeline(session, TimelineFeed.Local, limit = 1).getOrThrow()
            repository.getTimeline(session, TimelineFeed.Federated, limit = 1).getOrThrow()

            assertEquals(
                "/api/v1/timelines/public?local=true&remote=false&limit=1",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/v1/timelines/public?local=false&remote=false&limit=1",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun loadsOwnProfileAndStatuses() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/accounts/me" -> MockResponse().setBody(
                        """{"id":"me","username":"alice","acct":"alice","display_name":"Alice","note":"<p>Bio</p>","followers_count":12,"following_count":8,"statuses_count":34}""",
                    )
                    "/api/v1/accounts/me/statuses?limit=20&exclude_reblogs=false" ->
                        MockResponse().setBody("[${basicStatusJson("profile-status")}]" )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val session = testSession(server).copy(accountId = "me")

            val profile = DefaultTimelineRepository(ApiClientFactory())
                .getProfile(session)
                .getOrThrow()

            assertEquals("Alice", profile.author.displayName)
            assertEquals(12, profile.followersCount)
            assertEquals("profile-status", profile.statuses.single().statusId)
        }
    }

    @Test
    fun loadsNotificationsAndSearchResults() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":"n1","type":"mention","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"status":${basicStatusJson("mentioned")}}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"accounts":[{"id":"a","username":"alice","acct":"alice"}],"statuses":[${basicStatusJson("found")}],"hashtags":[{"name":"android","url":"https://example.social/tags/android"}]}""",
                ),
            )
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val notifications = repository.getNotifications(session).getOrThrow().notifications
            val results = repository.search(session, "android").getOrThrow()

            assertEquals("mention", notifications.single().type)
            assertEquals("found", results.statuses.single().statusId)
            assertEquals("android", results.hashtags.single().name)
            assertEquals("/api/v1/notifications?limit=40", server.takeRequest().path)
            assertEquals("/api/v2/search?q=android&limit=20&resolve=false", server.takeRequest().path)
        }
    }

    @Test
    fun loadsServerAnnouncements() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":"notice-1","content":"<p>Maintenance</p>","published_at":"2026-09-08T00:00:00Z","read":false}]""",
                ),
            )

            val announcements = DefaultTimelineRepository(ApiClientFactory())
                .getAnnouncements(testSession(server))
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("/api/v1/announcements", request.path)
            assertEquals("notice-1", announcements.single().id)
            assertEquals("<p>Maintenance</p>", announcements.single().contentHtml)
        }
    }

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

    @Test
    fun loadsStatusDetailAndMapsApplicationAndFedibirdReactions() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/statuses/42" -> MockResponse().setBody(
                        """
                        {
                          "id":"42","created_at":"2026-09-08T00:00:00Z",
                          "account":{"id":"author","username":"alice","acct":"alice","display_name":"Alice"},
                          "content":"<p>Hello</p>",
                          "application":{"name":"Tusky","website":"https://example.com"},
                          "emoji_reactions":[{"name":"👍","count":3,"me":true,"account_ids":["me"]}]
                        }
                        """.trimIndent(),
                    )
                    "/api/v1/statuses/42/context" ->
                        MockResponse().setBody("""{"ancestors":[],"descendants":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val session = AccountSession(
                sessionId = "session",
                instanceUrl = server.url("/").toString().removeSuffix("/"),
                accountId = "me",
                username = "me",
                displayName = "Me",
                avatarUrl = "",
                accessToken = "token",
            )

            val detail = DefaultTimelineRepository(ApiClientFactory())
                .getStatusDetail(session, "42")
                .getOrThrow()

            assertEquals(
                setOf("/api/v1/statuses/42", "/api/v1/statuses/42/context"),
                setOf(server.takeRequest().path, server.takeRequest().path),
            )
            assertEquals("Tusky", detail.status.applicationName)
            assertEquals("👍", detail.status.reactions.single().name)
            assertEquals(true, detail.status.reactions.single().reactedByMe)
            assertEquals(true, detail.status.supportsEmojiReactions)
        }
    }

    @Test
    fun postsReplyWithIdempotencyKey() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("99")))
            val session = testSession(server)

            DefaultTimelineRepository(ApiClientFactory())
                .createStatus(session, "返信です", "42", "request-key")
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/statuses", request.path)
            assertEquals("request-key", request.getHeader("Idempotency-Key"))
            val body = request.body.readUtf8()
            assertEquals(true, body.contains("status=%E8%BF%94%E4%BF%A1%E3%81%A7%E3%81%99"))
            assertEquals(true, body.contains("in_reply_to_id=42"))
        }
    }

    @Test
    fun mapsPreviewCardAndDistinguishesUnsupportedReactions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    [${basicStatusJson("1").dropLast(1)},
                      "card":{"url":"https://example.com","title":"Example","description":"Summary","image":"https://example.com/image.jpg","width":1200,"height":630}
                    }]
                    """.trimIndent(),
                ),
            )
            val page = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server))
                .getOrThrow()

            assertEquals("Example", page.statuses.single().previewCard?.title)
            assertFalse(page.statuses.single().supportsEmojiReactions)
        }
    }

    @Test
    fun favouritesAndAddsFedibirdReactionUsingExpectedEndpoints() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            repository.setFavourite(session, "1", true).getOrThrow()
            repository.setFedibirdReaction(session, "1", "👍").getOrThrow()

            val favouriteRequest = server.takeRequest()
            assertEquals("POST", favouriteRequest.method)
            assertEquals("/api/v1/statuses/1/favourite", favouriteRequest.path)
            val reactionRequest = server.takeRequest()
            assertEquals("PUT", reactionRequest.method)
            assertEquals("/api/v1/statuses/1/emoji_reactions/%F0%9F%91%8D", reactionRequest.path)
        }
    }

    private fun testSession(server: MockWebServer) = AccountSession(
        sessionId = "session",
        instanceUrl = server.url("/").toString().removeSuffix("/"),
        accountId = "me",
        username = "me",
        displayName = "Me",
        avatarUrl = "",
        accessToken = "token",
    )

    private fun basicStatusJson(id: String) =
        """{"id":"$id","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"content":"hello"}"""
}
