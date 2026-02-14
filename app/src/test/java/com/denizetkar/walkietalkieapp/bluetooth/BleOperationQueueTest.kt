package com.denizetkar.walkietalkieapp

import com.denizetkar.walkietalkieapp.bluetooth.BleOperationQueue
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleOperationQueueTest {

    @Test
    fun `Serialization - Operations run one at a time`() = runTest(UnconfinedTestDispatcher()) {
        val queue = BleOperationQueue(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val executionLog = mutableListOf<String>()

        queue.enqueue(TransportDataType.CONTROL) {
            executionLog.add("Start Op1")
            delay(100)
            executionLog.add("End Op1")
        }

        queue.enqueue(TransportDataType.AUDIO) {
            executionLog.add("Start Op2")
            executionLog.add("End Op2")
        }

        advanceTimeBy(200)

        val expected = listOf("Start Op1", "End Op1", "Start Op2", "End Op2")
        assertEquals(expected, executionLog)
    }

    @Test
    fun `Priority - Control takes precedence over Audio`() = runTest(UnconfinedTestDispatcher()) {
        val queue = BleOperationQueue(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val executionLog = mutableListOf<String>()

        // 1. BLOCK THE ACTOR (Use Control so it consumes priority if any)
        queue.enqueue(TransportDataType.CONTROL) { delay(10) }

        // 2. FILL THE QUEUE
        queue.enqueue(TransportDataType.AUDIO) { executionLog.add("Audio") }
        queue.enqueue(TransportDataType.CONTROL) { executionLog.add("Control") }

        // 3. UNBLOCK
        advanceTimeBy(20)

        assertEquals(listOf("Control", "Audio"), executionLog)
    }

    @Test
    fun `Starvation Protection - Audio runs after 4 consecutive Control ops`() = runTest(UnconfinedTestDispatcher()) {
        val queue = BleOperationQueue(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val executionLog = mutableListOf<String>()

        // 1. BLOCK THE ACTOR with AUDIO
        // FIX: Using AUDIO here resets the starvation counter to 0, ensuring a clean start for the test.
        queue.enqueue(TransportDataType.AUDIO) { delay(10) }

        // 2. FILL THE QUEUE
        repeat(5) { i ->
            queue.enqueue(TransportDataType.CONTROL) { executionLog.add("Control$i") }
        }
        queue.enqueue(TransportDataType.AUDIO) { executionLog.add("Audio") }
        queue.enqueue(TransportDataType.CONTROL) { executionLog.add("ControlFinal") }

        // 3. UNBLOCK
        advanceTimeBy(100)

        // Logic:
        // Blocker (Audio) -> Count = 0
        // C0 -> Count = 1
        // C1 -> Count = 2
        // C2 -> Count = 3
        // C3 -> Count = 4
        // Loop Check: Starving (4>=4)? YES -> Pick Audio. Count = 0.
        // C4 -> Count = 1
        // ControlFinal

        val expected = listOf(
            "Control0", "Control1", "Control2", "Control3",
            "Audio",
            "Control4", "ControlFinal"
        )

        assertEquals(expected, executionLog)
    }
}