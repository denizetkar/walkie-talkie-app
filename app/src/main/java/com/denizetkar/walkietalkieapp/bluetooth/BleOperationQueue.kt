package com.denizetkar.walkietalkieapp.bluetooth

import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

class BleOperationQueue(
    private val scope: CoroutineScope,
    private val onFatalError: () -> Unit
) {
    private val controlQueue = ArrayDeque<suspend () -> Unit>()
    private val audioQueue = ArrayDeque<suspend () -> Unit>()

    // STATE
    private var isBusy = false
    private var consecutiveControlCount = 0

    private val mutex = Mutex()
    private var timeoutJob: Job? = null

    fun enqueue(type: TransportDataType, action: suspend () -> Unit) {
        // Use the injected scope. If the Client dies, this launch is cancelled automatically.
        scope.launch {
            mutex.withLock {
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
                    processNextLocked()
                }
            }
        }
    }

    fun operationCompleted() {
        scope.launch {
            mutex.withLock {
                timeoutJob?.cancel()
                isBusy = false
                processNextLocked()
            }
        }
    }

    // Must be called inside mutex.withLock
    private fun processNextLocked() {
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
            // We don't need to lock here to set isBusy false because we are killing the connection anyway
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

    fun clear() {
        scope.launch {
            mutex.withLock {
                timeoutJob?.cancel()
                controlQueue.clear()
                audioQueue.clear()
                isBusy = false
                consecutiveControlCount = 0
            }
        }
    }

    fun shutdown() {
        clear()
        // We don't cancel the scope here because we don't own it anymore.
        // The parent (GattClientHandler) owns the scope.
    }
}