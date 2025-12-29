package com.denizetkar.walkietalkieapp.bluetooth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executors

/**
 * Android BLE is synchronous. You cannot fire two operations (Write, Read, DescWrite) at once.
 * This queue serializes operations.
 */
class BleOperationQueue {
    private val queue: Queue<Runnable> = LinkedList()
    private var isBusy = false
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @Synchronized
    fun enqueue(operation: Runnable) {
        queue.add(operation)
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
        val operation = queue.poll()
        if (operation != null) {
            isBusy = true
            scope.launch {
                try {
                    operation.run()
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