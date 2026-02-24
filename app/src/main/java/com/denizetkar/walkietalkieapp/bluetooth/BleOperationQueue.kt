package com.denizetkar.walkietalkieapp.bluetooth

import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * A prioritized, serializing executor for BLE operations.
 * Ensures that only one BLE operation runs at a time, preventing "GATT Busy" errors.
 */
class BleOperationQueue(
    parentScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // The Actor lives as long as the parent (Connection/Server) lives.
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + ioDispatcher
    )

    // Control: Unlimited buffer. We must never drop a handshake or heartbeat.
    private val controlChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Audio: Fixed buffer. If the stack is too slow, we drop old audio frames.
    private val audioChannel = Channel<suspend () -> Unit>(
        capacity = Config.MAX_AUDIO_QUEUE_CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private var consecutiveControlCount = 0

    init {
        startActorLoop()
    }

    fun enqueue(type: TransportDataType, action: suspend () -> Unit) {
        if (!scope.isActive) return

        val channel = when (type) {
            TransportDataType.CONTROL -> controlChannel
            TransportDataType.AUDIO -> audioChannel
        }
        // trySend is non-blocking and safe for these channel types.
        channel.trySend(action)
    }

    private fun startActorLoop() {
        scope.launch {
            while (isActive) {
                // 1. Select the next operation based on priority
                val nextOp = pickNextOperation()

                // 2. Execute with a hard timeout
                withTimeout(Config.BLE_OPERATION_TIMEOUT) {
                    // This block suspends until the BLE callback fires (or fails)
                    nextOp()
                }
                // If the operation times out, we should never reach here.
                // Because `shutdown()` should be called already.

                // 3. Yield briefly to allow other coroutines (like the cancellation handler) to run
                yield()
            }
        }
    }

    /**
     * Deterministic Priority Logic
     */
    private suspend fun pickNextOperation(): suspend () -> Unit {
        val isStarving = consecutiveControlCount >= Config.AUDIO_STARVATION_THRESHOLD

        // STEP 1: Starvation Override
        // If we are starving, we check Audio FIRST.
        if (isStarving) {
            val audioOp = audioChannel.tryReceive().getOrNull()
            if (audioOp != null) {
                consecutiveControlCount = 0
                return audioOp
            }
        }

        // STEP 2: Strict Control Priority
        // If not starving (or audio was empty), we check Control.
        val controlOp = controlChannel.tryReceive().getOrNull()
        if (controlOp != null) {
            consecutiveControlCount++
            return controlOp
        }

        // STEP 3: Wait for Anything
        // If both were empty, we suspend until one arrives.
        return select {
            controlChannel.onReceive { op ->
                consecutiveControlCount++
                op
            }
            audioChannel.onReceive { op ->
                consecutiveControlCount = 0
                op
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}