package com.denizetkar.walkietalkieapp.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    // =========================================================================
    // Suspending Retry Tests (retryWithBackoff)
    // =========================================================================

    @Test
    fun `retryWithBackoff - returns immediately on success`() = runTest {
        var attempts = 0
        val result = retryWithBackoff(times = 3, initialDelay = 1000L) {
            attempts++
            "Success"
        }

        assertEquals("Success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retryWithBackoff - retries on failure and eventually succeeds`() = runTest {
        var attempts = 0
        val result = retryWithBackoff(times = 5, initialDelay = 1000L, factor = 2.0) {
            attempts++
            if (attempts < 3) throw IOException("Temporary Network Failure")
            "Recovered"
        }

        assertEquals("Recovered", result)
        assertEquals(3, attempts)
    }

    @Test(expected = IOException::class)
    fun `retryWithBackoff - throws last exception when exhausted`() = runTest {
        retryWithBackoff(times = 3, initialDelay = 1000L) {
            throw IOException("Fatal Error")
        }
    }

    @Test(expected = CancellationException::class)
    fun `retryWithBackoff - NEVER swallows CancellationException`() = runTest {
        var attempts = 0
        retryWithBackoff(times = 5, initialDelay = 1000L) {
            attempts++
            // Simulating a coroutine cancellation (e.g. user navigated away)
            throw CancellationException("Job Cancelled")
        }
        // If the exception was swallowed, it would retry 5 times.
        // Because it's immediately rethrown, attempts should be exactly 1.
        assertEquals(1, attempts)
    }

    // =========================================================================
    // Blocking Retry Tests (retryWithBackoffNullable)
    // =========================================================================

    @Test
    fun `retryWithBackoffNullable - returns immediately on non-null`() {
        var attempts = 0
        val result = retryWithBackoffNullable(times = 3, initialDelay = 1L) {
            attempts++
            "Token"
        }

        assertEquals("Token", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retryWithBackoffNullable - retries on null and eventually succeeds`() {
        var attempts = 0
        val result = retryWithBackoffNullable(times = 5, initialDelay = 1L) {
            attempts++
            if (attempts < 3) null else "Valid Data"
        }

        assertEquals("Valid Data", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retryWithBackoffNullable - returns null when exhausted`() {
        var attempts = 0
        val result = retryWithBackoffNullable(times = 3, initialDelay = 1L) {
            attempts++
            null // Always fails
        }

        assertNull("Should return null after all attempts fail", result)
        assertEquals(3, attempts)
    }
}