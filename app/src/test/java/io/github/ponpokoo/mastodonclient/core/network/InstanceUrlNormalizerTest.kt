package io.github.ponpokoo.mastodonclient.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceUrlNormalizerTest {
    @Test
    fun addsHttpsAndRemovesTrailingSlash() {
        assertEquals(
            "https://mastodon.social",
            InstanceUrlNormalizer.normalize(" mastodon.social/ ").getOrThrow(),
        )
    }

    @Test
    fun rejectsHttp() {
        assertTrue(InstanceUrlNormalizer.normalize("http://mastodon.social").isFailure)
    }

    @Test
    fun rejectsPath() {
        assertTrue(InstanceUrlNormalizer.normalize("https://mastodon.social/about").isFailure)
    }
}
