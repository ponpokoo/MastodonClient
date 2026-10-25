package io.github.ponpokoo.mastodonclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.NetworkAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkAvailabilityDeviceTest {
    @Test fun cancellingCollectorsReleasesPlatformCallbacks() = runBlocking {
        val source = NetworkAvailability(InstrumentationRegistry.getInstrumentation().targetContext)
        // More than Android's per-UID callback limit: leaking each cancelled collector
        // would exhaust the quota. No device connectivity settings are changed.
        withTimeout(30_000) {
            repeat(110) { source.observeChanges().first() }
        }
    }
}
