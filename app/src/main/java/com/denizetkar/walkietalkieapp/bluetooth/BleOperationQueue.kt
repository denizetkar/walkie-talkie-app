package com.denizetkar.walkietalkieapp.bluetooth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue

data class PrioritizedRunnable(
    val priority: Int,
    val timestamp: Long,
    val action: Runnable
) : Comparable<PrioritizedRunnable> {
    override fun compareTo(other: PrioritizedRunnable): Int {
        if (this.priority != other.priority) {
            return this.priority - other.priority
        }
        return this.timestamp.compareTo(other.timestamp)
    }
}

class BleOperationQueue {
    private val queue = PriorityBlockingQueue<PrioritizedRunnable>()
    private var isBusy = false
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @Synchronized
    fun enqueue(priority: Int, operation: Runnable) {
        queue.add(PrioritizedRunnable(priority, System.nanoTime(), operation))
        if (!isBusy) {
            processNext()
        }
    }

    @Synchronized
    fun operationCompleted() {
        isBusy = false
        processNext()
    }

    @Synchronized
    private fun processNext() {
        if (isBusy) return
        val item = queue.poll()
        if (item != null) {
            isBusy = true
            scope.launch {
                try {
                    item.action.run()
                } catch (e: Exception) {
                    Log.e("BleQueue", "Operation failed", e)
                    isBusy = false
                    processNext()
                }
            }
        }
    }

    fun clear() {
        queue.clear()
        isBusy = false
    }
}