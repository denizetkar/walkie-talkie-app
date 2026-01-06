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
    private val controlQueue = ArrayDeque<suspend () -> Unit>()
    private val audioQueue = ArrayDeque<suspend () -> Unit>()

    // STATE
    private var isBusy = false
    private var consecutiveControlCount = 0
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var timeoutJob: Job? = null

    @Synchronized
    fun enqueue(type: TransportDataType, action: suspend () -> Unit) {
        if (type == TransportDataType.CONTROL) {
            controlQueue.add(action)
        } else {
            // Drop the oldest packet (Head) to make room for the newest (Tail)
            if (audioQueue.size >= Config.MAX_AUDIO_QUEUE_CAPACITY) {
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

        // SMART RULE 2: Starvation Prevention
        // Check if we should force an Audio packet
        val forceAudio = (consecutiveControlCount >= Config.AUDIO_STARVATION_THRESHOLD) && !audioQueue.isEmpty()

        val nextAction = if (forceAudio) {
            // Starvation Avoidance Mode: Let one Audio packet sneak in
            consecutiveControlCount = 0
            audioQueue.pollFirst()
        } else {
            if (!controlQueue.isEmpty()) {
                consecutiveControlCount++
                controlQueue.pollFirst()
            } else {
                consecutiveControlCount = 0
                audioQueue.pollFirst()
            }
        }

        if (nextAction != null) {
            executeOperation(nextAction)
        }
    }

    private fun executeOperation(action: suspend () -> Unit) {
        isBusy = true
        timeoutJob = scope.launch {
            delay(Config.BLE_OPERATION_TIMEOUT)
            Log.e("BleQueue", "Operation STALLED. Stack wedged. Disconnecting.")
            isBusy = false
            onFatalError()
        }

        scope.launch {
            try {
                action()
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
        consecutiveControlCount = 0
    }
}