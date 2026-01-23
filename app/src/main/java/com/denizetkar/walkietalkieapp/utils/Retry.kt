package com.denizetkar.walkietalkieapp.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Executes a block with retry logic.
 *
 * CRITICAL ARCHITECTURAL RULE:
 * This function explicitly checks for [CancellationException] and re-throws it immediately.
 * This prevents the "Zombie Operation" bug where a cancelled operation keeps retrying,
 * clogging the BLE queue.
 */
suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long,
    factor: Double = 1.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            // THE FIX: Never swallow cancellation
            if (e is CancellationException) throw e

            // Log or just wait
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong()
        }
    }
    // Final attempt (lets exception propagate if it fails)
    return block()
}