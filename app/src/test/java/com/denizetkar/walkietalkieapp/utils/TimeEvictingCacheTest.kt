package com.denizetkar.walkietalkieapp.utils

import org.junit.Assert.*
import org.junit.Test

class TimeEvictingCacheTest {

    @Test
    fun `Put and Contains - Works like a standard set`() {
        val cache = TimeEvictingCache<String>(1000L)
        cache.put("PacketA", 100L)

        assertTrue(cache.contains("PacketA"))
        assertFalse(cache.contains("PacketB"))
    }

    @Test
    fun `Cleanup - Evicts expired items and stops at valid items`() {
        val cache = TimeEvictingCache<String>(1000L)

        // Insert items at different times
        cache.put("Packet1", 100L)
        cache.put("Packet2", 500L)
        cache.put("Packet3", 1500L)

        // Fast forward to time = 1400L.
        // Packet1 (100L) is 1300ms old -> EXPIRED (> 1000)
        // Packet2 (500L) is 900ms old -> VALID
        // Packet3 (1500L) is in the future -> VALID
        cache.cleanup(1400L)

        assertFalse("Packet1 should be evicted", cache.contains("Packet1"))
        assertTrue("Packet2 should remain", cache.contains("Packet2"))
        assertTrue("Packet3 should remain", cache.contains("Packet3"))

        assertEquals("Map should have 2 items", 2, cache.size)
        assertEquals("Queue should have 2 items", 2, cache.queueSize)
    }

    @Test
    fun `Lazy Deletion - Updating an existing item prevents its eviction`() {
        val cache = TimeEvictingCache<String>(1000L)

        // 1. Initial insertion
        cache.put("EchoPacket", 100L)

        // Queue size: 1, Map size: 1
        assertEquals(1, cache.queueSize)

        // 2. The packet is seen again (updated) before it expires
        cache.put("EchoPacket", 800L)

        // Queue size: 2 (Lazy duplicate), Map size: 1 (Overwritten)
        assertEquals(2, cache.queueSize)
        assertEquals(1, cache.size)

        // 3. Fast forward to time = 1200L
        // The first queue entry (100L) is 1100ms old -> EXPIRED.
        // The second queue entry (800L) is 400ms old -> VALID.
        cache.cleanup(1200L)

        // 4. Assert Lazy Deletion Behavior
        assertTrue("Packet should still be valid due to update", cache.contains("EchoPacket"))
        assertEquals("The expired ghost entry should be dropped from the queue", 1, cache.queueSize)
        assertEquals("Map should remain intact", 1, cache.size)
    }

    @Test
    fun `Clear - Resets all internal structures`() {
        val cache = TimeEvictingCache<String>(1000L)
        cache.put("A", 100L)
        cache.put("B", 100L)

        cache.clear()

        assertFalse(cache.contains("A"))
        assertEquals(0, cache.size)
        assertEquals(0, cache.queueSize)
    }

    @Test
    fun `Memory Leak Check - Massive updates do not overflow the queue after cleanup`() {
        val cache = TimeEvictingCache<String>(1000L)

        // Simulate a high-frequency packet being updated 100 times over 2 seconds
        var time = 0L
        repeat(100) {
            cache.put("HotPacket", time)
            time += 20L // 20ms steps
        }

        // Before cleanup, the queue has 100 ghost entries for a single map key
        assertEquals(100, cache.queueSize)
        assertEquals(1, cache.size)

        // Run cleanup at the end of the timeline (time = 2000L)
        // Everything older than 1000L (the first 50 entries) should be evicted.
        cache.cleanup(time)

        // The queue should now only contain the entries from the last 1000ms.
        assertEquals(50, cache.queueSize)
        assertEquals(1, cache.size)
    }
}
