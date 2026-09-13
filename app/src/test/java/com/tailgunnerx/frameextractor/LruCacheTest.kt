package com.tailgunnerx.frameextractor

import com.tailgunnerx.frameextractor.util.LruCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LruCacheTest {

    private fun stringCache(maxWeight: Long) = LruCache<String, String>(maxWeight) { it.length.toLong() }

    @Test
    fun storesAndReturnsValues() {
        val cache = stringCache(100)
        cache.put("a", "one")
        assertEquals("one", cache.get("a"))
        assertNull(cache.get("missing"))
        assertEquals(3L, cache.currentWeight)
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() {
        val cache = stringCache(10)
        cache.put("a", "1111")   // 4
        cache.put("b", "2222")   // 4 => 8
        cache.get("a")           // "a" is now more recent than "b"
        cache.put("c", "3333")   // 12 > 10, evicts "b"
        assertNull(cache.get("b"))
        assertSame("1111", cache.get("a"))
        assertSame("3333", cache.get("c"))
        assertEquals(8L, cache.currentWeight)
    }

    @Test
    fun replacingAKeyUpdatesTheWeight() {
        val cache = stringCache(100)
        cache.put("a", "1111")
        cache.put("a", "1")
        assertEquals(1L, cache.currentWeight)
        assertEquals(1, cache.size)
    }

    @Test
    fun refusesEntriesThatCouldNeverFit() {
        val cache = stringCache(4)
        cache.put("a", "12345")
        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = stringCache(100)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.currentWeight)
    }

    @Test
    fun staysWithinBudgetUnderHeavyUse() {
        val cache = stringCache(64)
        repeat(500) { index ->
            cache.put("key$index", "v".repeat(1 + index % 7))
        }
        assertTrue("weight ${cache.currentWeight} exceeded budget", cache.currentWeight <= 64L)
    }
}
