package com.tailgunnerx.frameextractor.util

/**
 * Minimal thread-safe LRU cache with a weight budget.
 *
 * Deliberately free of Android types so the eviction policy can be unit tested on the JVM.
 * Used to keep a handful of already-decoded preview frames around, which makes stepping back and
 * forth over the same frames instant instead of re-seeking the decoder every time.
 */
class LruCache<K : Any, V : Any>(
    private val maxWeight: Long,
    private val weigh: (V) -> Long,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<K, V>(0, 0.75f, /* accessOrder = */ true)
    private var weight = 0L

    val size: Int
        get() = synchronized(lock) { entries.size }

    val currentWeight: Long
        get() = synchronized(lock) { weight }

    fun get(key: K): V? = synchronized(lock) { entries[key] }

    /** Returns the value that was evicted to make room for [key], if any. */
    fun put(key: K, value: V): V? {
        val valueWeight = weigh(value)
        // A single entry that could never fit would just evict everything else and still be
        // dropped on the next insert, so refuse it up front.
        if (valueWeight > maxWeight) return null
        synchronized(lock) {
            val previous = entries.put(key, value)
            weight += valueWeight - (previous?.let(weigh) ?: 0L)
            trim()
            return previous
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            weight = 0L
        }
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (weight > maxWeight && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            weight -= weigh(eldest.value)
        }
    }
}
