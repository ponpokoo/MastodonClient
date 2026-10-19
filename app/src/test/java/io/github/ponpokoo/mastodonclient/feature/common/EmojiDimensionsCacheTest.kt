package io.github.ponpokoo.mastodonclient.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDimensionsCacheTest {
    @Test fun retainedPostKeepsAllDimensionsBeyondTheRecentUrlLimit() {
        val cache = EmojiDimensionsCache()
        val owner = Any()
        val ratios = (0 until 600).associate { "emoji:$it" to (it + 1f) }
        cache.put(owner, ratios)
        assertEquals(ratios, cache.get(owner, ratios.keys.toList()))
        // The independent recent-URL tier is still bounded.
        assertEquals(512, cache.get(Any(), ratios.keys.toList()).size)
    }

    @Test fun otherPostsCannotEvictDimensionsFromAHeldPost() {
        val cache = EmojiDimensionsCache(recentLimit = 2)
        val owner = Any()
        cache.put(owner, mapOf("wide" to 5f, "tall" to .25f))
        cache.put(Any(), mapOf("a" to 1f, "b" to 2f, "c" to 3f))
        assertEquals(mapOf("wide" to 5f, "tall" to .25f), cache.get(owner, listOf("wide", "tall")))
    }

    @Test fun differentTextViewsOfTheSameOwnerKeepEachOthersDimensions() {
        val cache = EmojiDimensionsCache(recentLimit = 1)
        val owner = Any()
        cache.put(owner, mapOf("body" to 5f))
        cache.put(owner, mapOf("other-view" to .25f))
        assertEquals(mapOf("body" to 5f, "other-view" to .25f), cache.get(owner, listOf("body", "other-view")))
    }

    @Test fun equalButDistinctOwnersRetainTheirOwnSnapshots() {
        val cache = EmojiDimensionsCache(recentLimit = 1)
        val first = listOf("post")
        val second = listOf("post")
        cache.put(first, mapOf("emoji" to 2f))
        cache.put(second, mapOf("emoji" to 5f))
        assertEquals(mapOf("emoji" to 2f), cache.get(first, listOf("emoji")))
        assertEquals(mapOf("emoji" to 5f), cache.get(second, listOf("emoji")))
    }

    @Test fun changedUrlsAndInvalidDimensionsDoNotReuseUnrelatedGeometry() {
        val cache = EmojiDimensionsCache()
        val owner = Any()
        cache.put(owner, mapOf("old" to 5f, "zero" to 0f, "negative" to -1f, "nan" to Float.NaN, "infinite" to Float.POSITIVE_INFINITY))
        assertTrue(cache.get(owner, listOf("new", "zero", "negative", "nan", "infinite")).isEmpty())
        assertEquals(mapOf("old" to 5f), cache.get(owner, listOf("old")))
    }
}
