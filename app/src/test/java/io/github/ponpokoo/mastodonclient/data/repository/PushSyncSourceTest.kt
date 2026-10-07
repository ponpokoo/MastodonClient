package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PushSyncSourceTest {
    @Test fun usesSelectedInstanceCredentialsOpaqueCursorsAndIndividualNotifications() = runTest {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setBody("[]"))
            second.enqueue(MockResponse().setBody("""[{"id":"opaque-new","type":"future_type","created_at":"2026-10-07T00:00:00Z","account":{"id":"actor","username":"alice","acct":"alice"},"future_field":true}]"""))
            val source = MastodonPushSyncSource(ApiClientFactory())
            val a = AccountSession("a", first.url("/").toString(), "me", "me", "Me", "", "first-token")
            val b = a.copy(sessionId = "b", instanceUrl = second.url("/").toString(), accessToken = "second-token")
            assertNull(source.baseline(a))
            val page = source.page(b, "opaque/lower+bound", "opaque/upper+bound")
            assertEquals("opaque-new", page.single().id)
            val baseline = first.takeRequest()
            assertEquals("1", baseline.requestUrl!!.queryParameter("limit"))
            assertEquals("Bearer first-token", baseline.getHeader("Authorization"))
            val delta = second.takeRequest()
            assertEquals("/api/v1/notifications", delta.requestUrl!!.encodedPath)
            assertEquals("40", delta.requestUrl!!.queryParameter("limit"))
            assertEquals("opaque/lower+bound", delta.requestUrl!!.queryParameter("since_id"))
            assertEquals("opaque/upper+bound", delta.requestUrl!!.queryParameter("max_id"))
            assertEquals("Bearer second-token", delta.getHeader("Authorization"))
        } }
    }
}
