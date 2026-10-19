package io.github.ponpokoo.mastodonclient.feature.common

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Retain numeric metadata with its post data, without retaining posts, painters or image loaders. */
internal class EmojiDimensionsCache(private val recentLimit: Int = 512) {
    private val collectedOwners = ReferenceQueue<Any>()
    private val retained = HashMap<OwnerReference, Map<String, Float>>()
    private val recent = object : LinkedHashMap<String, Float>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > recentLimit
    }

    @Synchronized
    fun get(owner: Any, urls: List<String>): Map<String, Float> {
        removeCollectedOwners()
        val saved = retained[OwnerReference(owner)]
        return buildMap {
            urls.forEach { url -> (saved?.get(url) ?: recent[url])?.let { put(url, it) } }
        }
    }

    @Synchronized
    fun put(owner: Any, ratios: Map<String, Float>) {
        removeCollectedOwners()
        val valid = ratios.filterValues { it.isFinite() && it > 0f }
        if (valid.isEmpty()) return
        retained[OwnerReference(owner, collectedOwners)] = retained[OwnerReference(owner)].orEmpty() + valid
        valid.forEach { (url, ratio) -> recent[url] = ratio }
    }

    private fun removeCollectedOwners() {
        while (true) retained.remove(collectedOwners.poll() ?: return)
    }

    // Identity matters: collecting one of two equal post snapshots must not evict the other.
    private class OwnerReference(owner: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(owner, queue) {
        private val identity = System.identityHashCode(owner)
        override fun hashCode(): Int = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is OwnerReference && get()?.let { it === other.get() } == true)
    }
}
