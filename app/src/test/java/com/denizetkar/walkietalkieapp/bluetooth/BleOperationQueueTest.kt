package com.denizetkar.walkietalkieapp.bluetooth

import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `Flood - Drops OLD audio packets when queue is full`() = runTest(UnconfinedTestDispatcher()) {
        val queue = BleOperationQueue(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val completedAudioOps = mutableListOf<Int>()

        // 1. Block the actor so we can fill the buffer
        queue.enqueue(TransportDataType.CONTROL) { delay(100) }

        // 2. Flood Audio
        // Capacity is Config.MAX_AUDIO_QUEUE_CAPACITY (Default 8).
        // We try to add 20 items.
        // 0..19
        val totalFlood = 20
        repeat(totalFlood) { id ->
            queue.enqueue(TransportDataType.AUDIO) { completedAudioOps.add(id) }
        }

        // 3. Unblock
        advanceTimeBy(200)

        // 4. Assert
        // The Channel is configured with DROP_OLDEST.
        // If capacity is 8, it should keep the *last* 8 items added (12..19).
        // Items 0..11 should be dropped immediately during enqueue.
        val capacity = Config.MAX_AUDIO_QUEUE_CAPACITY
        assertEquals("Should only process capacity limit", capacity, completedAudioOps.size)

        val expected = ((totalFlood - capacity) until totalFlood).toList()
        assertEquals("Should keep NEWEST packets", expected, completedAudioOps)
    }

    @Test
    fun `Responsiveness - Control packet survives Audio flood`() = runTest(UnconfinedTestDispatcher()) {
        val queue = BleOperationQueue(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val log = mutableListOf<String>()

        // 1. Block actor
        queue.enqueue(TransportDataType.CONTROL) { delay(10) }

        // 2. Flood Audio (Fill buffer completely)
        repeat(15) { queue.enqueue(TransportDataType.AUDIO) { log.add("Audio") } }

        // 3. Add Critical Control Packet
        // Since Control Channel is UNLIMITED, this should be accepted.
        queue.enqueue(TransportDataType.CONTROL) { log.add("CriticalHandshake") }

        // 4. Unblock
        advanceTimeBy(100)

        // 5. Assert
        // We expect the CriticalHandshake to be present.
        assertTrue("Control packet must not be dropped", log.contains("CriticalHandshake"))

        // Bonus: It should probably run BEFORE most audio due to priority
        // (The priority test covers strict ordering, this covers survival).
    }
}