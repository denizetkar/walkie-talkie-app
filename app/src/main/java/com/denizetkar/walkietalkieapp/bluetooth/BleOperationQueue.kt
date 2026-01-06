package com.denizetkar.walkietalkieapp.bluetooth

import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class BleOperationQueue(
    dispatcher: CoroutineDispatcher,
    private val onFatalError: () -> Unit
) {
    // Standard FIFO for critical packets (Handshakes, Heartbeats)
    private val controlQueue = ArrayDeque<suspend () -> Unit>()

    // Bounded FIFO for voice packets.
    // We use ArrayDeque manually to enforce the "Head-Drop" policy efficiently.
    private val audioQueue = ArrayDeque<suspend () -> Unit>()

    private var isBusy = false
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var timeoutJob: Job? = null

    @Synchronized
    fun enqueue(type: TransportDataType, action: suspend () -> Unit) {
        if (type == TransportDataType.CONTROL) {
            controlQueue.add(action)
        } else {
            // O(1) Admission Control
            if (audioQueue.size >= Config.MAX_AUDIO_QUEUE_CAPACITY) {
                // Drop the oldest packet (Head) to make room for the newest (Tail)
                audioQueue.removeFirst()
                Log.v("BleQueue", "Dropped stale audio frame")
            }
            audioQueue.addLast(action)
        }

        if (!isBusy) {
            processNext()
        }
    }

    @Synchronized
    fun operationCompleted() {
        timeoutJob?.cancel()
        isBusy = false
        processNext()
    }

    @Synchronized
    private fun processNext() {
        if (isBusy) return

        // Priority Logic: Always drain Control first
        val nextAction = controlQueue.pollFirst() ?: audioQueue.pollFirst() ?: return

        isBusy = true
        timeoutJob = scope.launch {
            delay(Config.BLE_OPERATION_TIMEOUT)
            Log.e("BleQueue", "Operation STALLED. Stack wedged. Disconnecting.")
            isBusy = false
            onFatalError()
        }

        scope.launch {
            try {
                nextAction()
            } catch (e: Exception) {
                Log.e("BleQueue", "Operation failed", e)
                operationCompleted()
            }
        }
    }

    @Synchronized
    fun clear() {
        timeoutJob?.cancel()
        controlQueue.clear()
        audioQueue.clear()
        isBusy = false
    }
}