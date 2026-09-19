package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import io.github.ponpokoo.mastodonclient.domain.model.mentionedAccountIdFor
import io.github.ponpokoo.mastodonclient.domain.model.replyToAccountName
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTimelineRepositoryTest {
    @Test
    fun preservesLockedAccountsAndFollowRequestRelationships() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"private-one","username":"alice","acct":"alice","locked":true}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"id":"private-one","following":false,"followed_by":true,"requested":true}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val account = repository.getAccountListPage(session, "me", followers = true)
                .getOrThrow().accounts.single()
            val relationship = repository.getRelationships(session, listOf(account.id))
                .getOrThrow().getValue(account.id)

            assertTrue(account.locked)
            assertTrue(relationship.followedBy)
            assertTrue(relationship.requested)
            assertFalse(relationship.following)
        }
    }

    @Test
    fun followsUseLinkCursorInsteadOfLastAccountId() = runTest {
        MockWebServer().use { server ->
            val nextUrl = server.url("/api/v1/accounts/me/followers?limit=40&max_id=follow-edge-123")
            server.enqueue(MockResponse()
                .addHeader("Link", "<$nextUrl>; rel=\"next\"")
                .setBody("""[{"id":"account-999","username":"alice","acct":"alice"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":"account-500","username":"bob","acct":"bob"}]"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val first = repository.getAccountListPage(session, "me", followers = true).getOrThrow()
            assertEquals("follow-edge-123", first.nextMaxId)
            assertFalse(first.endReached)
            val second = repository.getAccountListPage(session, "me", followers = true, first.nextMaxId).getOrThrow()
            assertEquals(listOf("account-500"), second.accounts.map { it.id })
            assertTrue(second.endReached)
            server.takeRequest()
            assertEquals("follow-edge-123", server.takeRequest().requestUrl?.queryParameter("max_id"))
        }
    }

    @Test
    fun readsReactionAccountsFromPlainAndGroupedResponses() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"one","username":"alice","acct":"alice","display_name":"Alice"}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"name":"👍","accounts":[{"id":"two","username":"bob","acct":"bob"}]},{"name":"🎉","accounts":[{"id":"three","username":"cara","acct":"cara"}]}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"emoji":"👍","account":{"id":"four","username":"dan","acct":"dan"}}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            assertEquals(listOf("one"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().map { it.id })
            assertEquals(listOf("two"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().map { it.id })
            assertEquals(listOf("four"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().map { it.id })
        }
    }

    @Test
    fun mapsStatusAndAuthorEmojisFromTimelineResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"emoji-status","created_at":"2026-09-08T00:00:00Z","content":"<p>:electric:</p>","emojis":[{"shortcode":"electric","url":"https://example.com/electric.gif","static_url":"https://example.com/electric.png"}],"account":{"id":"a","username":"alice","acct":"alice","display_name":"Alice :twitch:","emojis":[{"shortcode":"twitch","url":"https://example.com/twitch.png"}]}}]""",
            ))

            val status = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single()

            assertEquals(mapOf("electric" to "https://example.com/electric.gif"), status.customEmojis)
            assertEquals(mapOf("twitch" to "https://example.com/twitch.png"), status.author.customEmojis)
        }
    }

    @Test
    fun mapsMentionAccountIdAndQuoteApproval() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"mention-status","created_at":"2026-09-08T00:00:00Z","in_reply_to_id":"parent-status","in_reply_to_account_id":"account-42","content":"<p><a href=\"https://social.example/@alice\">@alice</a></p>","mentions":[{"id":"account-42","username":"alice","acct":"alice@social.example","url":"https://social.example/@alice"}],"quote_approval":{"current_user":"automatic","automatic":[],"manual":[]},"account":{"id":"author","username":"bob","acct":"bob"}}]""",
            ))

            val status = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single()

            assertEquals("account-42", status.mentionedAccountIdFor("https://social.example/@alice/"))
            assertEquals(null, status.mentionedAccountIdFor("https://unrelated.example/@alice"))
            assertEquals("automatic", status.quoteApproval)
            assertEquals("parent-status", status.inReplyToId)
            assertEquals("account-42", status.inReplyToAccountId)
            assertEquals("alice@social.example", status.replyToAccountName())
        }
    }

    @Test
    fun cachedStatusesAreIsolatedByAccountAndInstance() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("shared")} ]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            repository.getHomeTimeline(session).getOrThrow()
            assertEquals("shared", repository.getCachedStatus(session, "shared")?.statusId)
            val other = session.copy(sessionId = "second")
            server.enqueue(MockResponse().setBody("[${basicStatusJson("shared").replace("hello", "other account")}]"))
            repository.getHomeTimeline(other).getOrThrow()
            assertEquals("hello", repository.getCachedStatus(session, "shared")?.contentHtml)
            assertEquals("other account", repository.getCachedStatus(other, "shared")?.contentHtml)
            assertEquals(null, repository.getCachedStatus(session.copy(sessionId = "other"), "shared"))
            assertEquals(null, repository.getCachedStatus(session.copy(instanceUrl = "https://other.social"), "shared"))
        }
    }

    @Test
    fun statusCacheEvictsOldEntries() = runTest {
        MockWebServer().use { server ->
            val statuses = (0..500).joinToString(",") { basicStatusJson(it.toString()) }
            server.enqueue(MockResponse().setBody("[$statuses]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            repository.getHomeTimeline(session).getOrThrow()
            assertEquals(null, repository.getCachedStatus(session, "0"))
            assertEquals("500", repository.getCachedStatus(session, "500")?.statusId)
        }
    }

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
            val requestsStarted = java.util.concurrent.CountDownLatch(3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestsStarted.countDown()
                    if (!requestsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        return MockResponse().setResponseCode(504)
                    }
                    return when (request.path) {
                    "/api/v1/accounts/me" -> MockResponse().setBody(
                        """{"id":"me","username":"alice","acct":"alice","display_name":"Alice","note":"<p>Bio</p>","followers_count":12,"following_count":8,"statuses_count":34}""",
                    )
                    "/api/v1/accounts/me/statuses?limit=20&exclude_reblogs=false&exclude_replies=true&only_media=false&pinned=false" ->
                        MockResponse().setBody("[${basicStatusJson("profile-status")}]" )
                    "/api/v1/accounts/me/statuses?limit=20&exclude_reblogs=false&exclude_replies=false&only_media=false&pinned=true" ->
                        MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                    }
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

            val notificationPage = repository.getNotifications(session).getOrThrow()
            val notifications = notificationPage.notifications
            val results = repository.search(session, "android").getOrThrow()

            assertEquals("mention", notifications.single().type)
            assertEquals("n1", notificationPage.nextMaxId)
            assertFalse(notificationPage.endReached)
            assertEquals("found", results.statuses.single().statusId)
            assertEquals("android", results.hashtags.single().name)
            assertEquals("/api/v1/notifications?limit=80", server.takeRequest().path)
            assertEquals("/api/v2/search?q=android&limit=20&resolve=false", server.takeRequest().path)
        }
    }

    @Test
    fun emptyNotificationPageEndsPagination() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))

            val page = DefaultTimelineRepository(ApiClientFactory())
                .getNotifications(testSession(server), maxId = "n1")
                .getOrThrow()

            assertTrue(page.notifications.isEmpty())
            assertNull(page.nextMaxId)
            assertTrue(page.endReached)
            assertEquals("/api/v1/notifications?max_id=n1&limit=80", server.takeRequest().path)
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
    fun postsCompleteComposerRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("100")))
            val session = testSession(server)

            DefaultTimelineRepository(ApiClientFactory()).createStatus(
                session = session,
                request = CreateStatusRequest(
                    text = "本文",
                    mediaIds = listOf("media-1"),
                    spoilerText = "注意",
                    sensitive = true,
                    visibility = "private",
                    language = "ja",
                ),
                idempotencyKey = "complete-key",
            ).getOrThrow()

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("complete-key", request.getHeader("Idempotency-Key"))
            assertEquals(true, body.contains("media_ids%5B%5D=media-1"))
            assertEquals(true, body.contains("visibility=private"))
            assertEquals(true, body.contains("sensitive=true"))
            assertEquals(true, body.contains("language=ja"))
        }
    }

    @Test
    fun postsNativeQuoteWithoutTurningItIntoAReply() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("quoted")))

            DefaultTimelineRepository(ApiClientFactory()).createStatus(
                testSession(server),
                CreateStatusRequest(text = "引用本文", quotedStatusId = "source-42"),
                "quote-key",
            ).getOrThrow()

            val body = server.takeRequest().body.readUtf8()
            assertEquals(true, body.contains("quoted_status_id=source-42"))
            assertEquals(false, body.contains("in_reply_to_id="))
        }
    }

    @Test
    fun readsComposerLimitsAndCustomEmoji() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"configuration":{"statuses":{"max_characters":800,"max_media_attachments":6},"media_attachments":{"description_limit":1200,"supported_mime_types":["image/png"]}}}""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"shortcode":"party","url":"https://cdn.example/party.gif","static_url":"https://cdn.example/party.png","category":"People"}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val limits = repository.getComposerConfiguration(session).getOrThrow()
            val emoji = repository.getCustomEmojis(session).getOrThrow().single()

            assertEquals(800, limits.maxCharacters)
            assertEquals(6, limits.maxMediaAttachments)
            assertEquals(1200, limits.mediaDescriptionLimit)
            assertEquals("party", emoji.shortcode)
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

    @Test
    fun reusesRemoteReactionWithItsServerDomain() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"1","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"emoji_reactions":[{"name":"ee","domain":"misskey.example","count":1,"url":"https://misskey.example/ee.png"}]}]""",
            ))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val reaction = repository.getHomeTimeline(session).getOrThrow().statuses.single().reactions.single()
            assertEquals("ee", reaction.name)
            assertEquals("ee@misskey.example", reaction.apiName)
            repository.setFedibirdReaction(session, "1", reaction.apiName).getOrThrow()

            server.takeRequest()
            assertEquals("/api/v1/statuses/1/emoji_reactions/ee@misskey.example", server.takeRequest().path)
        }
    }

    @Test
    fun recordsOnlySuccessfulNewReactions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val recorded = mutableListOf<String>()
            val repository = DefaultTimelineRepository(ApiClientFactory(),
                onReactionSucceeded = { _, emoji -> recorded += emoji })
            val session = testSession(server)

            repository.setFedibirdReaction(session, "1", "👍").getOrThrow()
            assertTrue(repository.setFedibirdReaction(session, "1", "🎉").isFailure)
            repository.setFedibirdReaction(session, "1", null).getOrThrow()

            assertEquals(listOf("👍"), recorded)
        }
    }

    @Test
    fun loadsListsAndSelectedListTimeline() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"friends","title":"友だち"}]"""))
            server.enqueue(MockResponse().setBody("[${basicStatusJson("list-status")}]") )
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val list = repository.getLists(session).getOrThrow().single()
            val page = repository.getSavedTimeline(session, SavedTimelineKind.List, list.id).getOrThrow()

            assertEquals("友だち", list.title)
            assertEquals("list-status", page.statuses.single().statusId)
            assertEquals("/api/v1/lists", server.takeRequest().path)
            assertEquals("/api/v1/timelines/list/friends?limit=20", server.takeRequest().path)
        }
    }

    @Test
    fun pinsAndUnpinsUsingExpectedEndpoints() = runTest {
        MockWebServer().use { server ->
            val pinned = basicStatusJson("1").dropLast(1) + ",\"pinned\":true}"
            server.enqueue(MockResponse().setBody(pinned))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            assertEquals(true, repository.setPinned(session, "1", true).getOrThrow().pinned)
            repository.setPinned(session, "1", false).getOrThrow()

            assertEquals("/api/v1/statuses/1/pin", server.takeRequest().path)
            assertEquals("/api/v1/statuses/1/unpin", server.takeRequest().path)
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
