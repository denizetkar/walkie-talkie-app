package com.denizetkar.walkietalkieapp.utils

import org.junit.Assert.*
import org.junit.Test

class ScanRateLimiterTest {

    @Test
    fun `Enforces 5 scans per 30 seconds limit`() {
        var currentTime = 1000L
        val limiter = ScanRateLimiter(timeSource = { currentTime })

        // 1. Consume all 5 tokens
        repeat(5) {
            assertNotNull("Attempt $it should succeed", limiter.tryAcquire())
        }

        // 2. 6th attempt should fail
        assertNull("6th attempt should be blocked", limiter.tryAcquire())

        // 3. Advance time by 10 seconds (Still within window)
        currentTime += 10_000
        assertNull("Should still be blocked", limiter.tryAcquire())

        // 4. Advance time past 30s window (30s + 1ms from start)
        currentTime += 20_001
        assertNotNull("Should allow new scan after window expires", limiter.tryAcquire())
    }

    @Test
    fun `Rollback returns token to the pool`() {
        val currentTime = 1000L
        val limiter = ScanRateLimiter(timeSource = { currentTime })

        // 1. Consume 5 tokens
        val tokens = mutableListOf<Long>()
        repeat(5) {
            val t = limiter.tryAcquire()
            assertNotNull(t)
            tokens.add(t!!)
        }

        // 2. Verify blocked
        assertNull(limiter.tryAcquire())

        // 3. Rollback one (Simulate scan failed to start)
        limiter.rollback(tokens[0])

        // 4. Verify we can acquire again immediately
        assertNotNull("Should allow acquire after rollback", limiter.tryAcquire())
    }
}