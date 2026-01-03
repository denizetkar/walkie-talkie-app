package com.denizetkar.walkietalkieapp.utils

import android.util.Log
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

    @Synchronized
    fun canStartScan(): Boolean {
        val now = System.currentTimeMillis()

        // 1. Prune old entries
        while (history.isNotEmpty() && (now - history.first) > windowMs) {
            history.removeFirst()
        }

        // 2. Check capacity
        if (history.size >= maxAttempts) {
            val waitTime = windowMs - (now - history.first)
            Log.w("ScanRateLimiter", "Rate Limit Hit! Waiting ${waitTime/1000}s before next scan.")
            return false
        }

        // 3. Record this attempt
        history.addLast(now)
        return true
    }
}