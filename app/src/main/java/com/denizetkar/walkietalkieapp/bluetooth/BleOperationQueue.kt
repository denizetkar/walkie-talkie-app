package com.denizetkar.walkietalkieapp.bluetooth

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.LinkedList
import java.util.Queue

/**
 * Android BLE is synchronous. You cannot fire two operations (Write, Read, DescWrite) at once.
 * This queue serializes operations.
 */
class BleOperationQueue {
    private val queue: Queue<Runnable> = LinkedList()
    private var isBusy = false
    private val handler = Handler(Looper.getMainLooper())

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
            // Run on main thread to be safe with BLE callbacks,
            // though most callbacks happen on binder threads.
            handler.post {
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