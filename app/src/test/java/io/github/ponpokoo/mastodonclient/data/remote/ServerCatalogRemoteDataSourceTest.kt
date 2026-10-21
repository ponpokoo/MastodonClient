package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ServerCatalogRemoteDataSourceTest {
    @Test fun directoryIncludesClosedRegistrationsWithoutAuthenticationAndIgnoresUnknownFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"domain":"one.example","future_field":true},{"description":"missing domain"},{"domain":null}]"""))
            val source = ServerCatalogRemoteDataSource(ServerCatalogRemoteDataSource.createApi(server.url("/").toString()))
            assertEquals(listOf("one.example"), source.loadDomains())
            val request = server.takeRequest()
            assertEquals("/servers?registrations=all", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }
}
