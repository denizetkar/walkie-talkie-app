package com.denizetkar.walkietalkieapp.utils

import java.util.ArrayDeque

/**
 * Android 7+ prevents starting a scan more than 5 times in 30 seconds.
 * This utility tracks scan attempts and blocks requests that would trigger
 * the system ban (Error Code 6).
 */
class ScanRateLimiter {
    private val history = ArrayDeque<Long>()

    // Android Limit: 5 scans in 30 seconds
    private val windowMs = 30_000L
    private val maxAttempts = 5

    /**
     * Atomically checks quota and reserves a slot if available.
     * Returns the timestamp (Token) if reserved, or null if quota exceeded.
     */
    @Synchronized
    fun tryAcquire(): Long? {
        val nowTimeMs = System.currentTimeMillis()
        prune(nowTimeMs)
        if (history.size >= maxAttempts) {
            return null
        }

        history.addLast(nowTimeMs)
        return nowTimeMs
    }

    /**
     * Returns a specific token to the pool.
     * Handles the case where multiple threads acquire tokens in parallel.
     */
    @Synchronized
    fun rollback(token: Long) {
        history.remove(token)
    }

    private fun prune(timeNowMs: Long) {
        while (history.isNotEmpty() && (timeNowMs - history.first) > windowMs) {
            history.removeFirst()
        }
    }
}