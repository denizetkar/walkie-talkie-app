package com.denizetkar.walkietalkieapp.utils

/**
 * An O(1) time-evicting cache optimized for monotonic time inputs.
 *
 * Architecture:
 * - [map] provides O(1) lookups and deduplication.
 * - [queue] provides an O(1) chronologically sorted eviction timeline.
 *
 * By appending every update to the queue and selectively ignoring outdated
 * queue entries during cleanup (Lazy Deletion), we avoid O(N) heap sorting
 * or O(N) full-map iterations.
 */
class TimeEvictingCache<K>(private val timeoutMs: Long) {
    private data class Entry<K>(val key: K, val timestamp: Long)

    private val map = HashMap<K, Long>()
    private val queue = ArrayDeque<Entry<K>>()

    /**
     * O(1) Insertion/Update.
     */
    fun put(key: K, timeMs: Long) {
        map[key] = timeMs
        // Always append to the back. We don't care if it's a duplicate key,
        // the lazy deletion logic will handle old entries.
        queue.addLast(Entry(key, timeMs))
    }

    /**
     * O(1) Lookup.
     */
    fun contains(key: K): Boolean {
        return map.containsKey(key)
    }

    /**
     * O(1) Amortized Cleanup.
     * Iterates only over expired elements at the front of the queue and stops instantly.
     */
    fun cleanup(currentTimeMs: Long) {
        while (queue.isNotEmpty()) {
            val oldest = queue.first()

            // Because time is monotonic, if the oldest element isn't expired,
            // nothing else after it is expired either. We stop instantly.
            if (currentTimeMs - oldest.timestamp <= timeoutMs) {
                break
            }

            // Remove the expired entry from the timeline
            queue.removeFirst()

            // Lazy Deletion Check:
            // Does the map still hold THIS EXACT timestamp for the key?
            // If yes: The item truly expired. Delete it.
            // If no: The item was updated later. A fresher entry exists further back in the queue. Ignore this one.
            val latestTime = map[oldest.key]
            if (latestTime == oldest.timestamp) {
                map.remove(oldest.key)
            }
        }
    }

    /**
     * Resets the cache entirely.
     */
    fun clear() {
        map.clear()
        queue.clear()
    }

    // Internal accessors for unit testing validation
    internal val size: Int get() = map.size
    internal val queueSize: Int get() = queue.size
}
