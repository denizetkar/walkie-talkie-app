package com.denizetkar.walkietalkieapp.bluetooth

import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.PriorityBlockingQueue

data class PrioritizedOperation(
    val priority: Int,
    val timestamp: Long,
    val action: suspend () -> Unit
) : Comparable<PrioritizedOperation> {
    override fun compareTo(other: PrioritizedOperation): Int {
        if (this.priority != other.priority) {
            return this.priority - other.priority
        }
        return this.timestamp.compareTo(other.timestamp)
    }
}

class BleOperationQueue(
    dispatcher: CoroutineDispatcher,
    private val onFatalError: () -> Unit
) {
    private val queue = PriorityBlockingQueue<PrioritizedOperation>()
    private var isBusy = false
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var timeoutJob: Job? = null

    @Synchronized
    fun enqueue(priority: Int, operation: suspend () -> Unit) {
        queue.add(PrioritizedOperation(priority, System.nanoTime(), operation))
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
        val item = queue.poll() ?: return

        isBusy = true
        timeoutJob = scope.launch {
            delay(Config.BLE_OPERATION_TIMEOUT)
            Log.e("BleQueue", "Operation STALLED (Timeout). Stack is wedged. Disconnecting.")
            isBusy = false
            onFatalError()
        }

        scope.launch {
            try {
                item.action()
            } catch (e: Exception) {
                Log.e("BleQueue", "Operation failed", e)
                operationCompleted()
            }
        }
    }

    @Synchronized
    fun clear() {
        timeoutJob?.cancel()
        queue.clear()
        isBusy = false
    }
}