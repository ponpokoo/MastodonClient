package io.github.ponpokoo.mastodonclient

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.remote.RelayMessageDataSource
import io.github.ponpokoo.mastodonclient.data.remote.RelayRegistrationDataSource
import io.github.ponpokoo.mastodonclient.notification.currentFcmToken
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.HttpException
import java.security.SecureRandom
import java.util.Base64

/** Explicit opt-in: contacts the configured relay and leaves one deletion tombstone. */
class RelayLiveConnectionDeviceTest {
    @Test fun configuredRelayRegistersAndRemovesActualFirebaseDestination() = runBlocking {
        assumeTrue("Live relay verification was not requested",
            InstrumentationRegistry.getArguments().getString("liveRelay") == "true")
        assertTrue("Firebase must be configured", BuildConfig.FIREBASE_CONFIGURED)
        assertTrue("Relay must be configured", BuildConfig.RELAY_URL.isNotBlank())
        val origin = BuildConfig.RELAY_URL.toHttpUrl()
        val relay = RelayRegistrationDataSource(BuildConfig.RELAY_URL)
        fun randomId() = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val id = randomId()
        val management = randomId()
        val token = withTimeout(60_000) { currentFcmToken() }
        assertTrue("Firebase returned an empty token", token.isNotBlank())
        var primaryFailure: Throwable? = null
        try {
            val first = relay.put(id, management, token).endpoint
            val endpoint = first.toHttpUrl()
            assertTrue("Unexpected delivery origin", endpoint.isHttps && endpoint.host == origin.host &&
                endpoint.port == origin.port && endpoint.encodedPath.startsWith("/push/"))
            assertTrue("Repeated registration changed endpoint", relay.put(id, management, token).endpoint == first)
            val forbidden = try {
                relay.put(id, randomId(), token)
                null
            } catch (error: HttpException) { error.code() }
            assertEquals("Incorrect management token was accepted", 403, forbidden)
            assertNull(RelayMessageDataSource().fetch(BuildConfig.RELAY_URL, id, randomId(), management))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // Do not leave the real device token stored in an unused trial registration.
            try {
                relay.remove(id, management)
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
        val retired = try {
            relay.put(id, management, token)
            null
        } catch (error: HttpException) { error.code() }
        assertEquals("Deleted registration could be revived", 410, retired)
    }
}
