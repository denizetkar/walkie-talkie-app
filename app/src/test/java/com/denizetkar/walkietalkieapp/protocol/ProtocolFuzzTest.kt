package com.denizetkar.walkietalkieapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class ProtocolFuzzTest {

    // Number of random iterations.
    // 100,000 is small enough to run instantly (<200ms) but large enough to catch edge cases.
    private val iterations = 100_000

    @Test
    fun `Fuzzing - Packet Parser never crashes on random garbage`() {
        var currentBytes: ByteArray? = null

        try {
            // 1. Edge Case: Empty
            Packet.fromBytes(byteArrayOf(), true)
            Packet.fromBytes(byteArrayOf(), false)

            // 2. Edge Case: Single Byte
            Packet.fromBytes(byteArrayOf(0x00), true)

            // 3. Fuzz Loop
            repeat(iterations) {
                // Generate random length (0 to 1024 bytes)
                val length = Random.nextInt(0, 1024)
                val bytes = Random.nextBytes(length)
                currentBytes = bytes

                // Test Control Mode
                Packet.fromBytes(bytes, isControlChar = true)

                // Test Audio Mode
                Packet.fromBytes(bytes, isControlChar = false)
            }
        } catch (e: Exception) {
            val hex = currentBytes?.joinToString("") { "%02x".format(it) }
            fail("CRASH DETECTED on input: [$hex]\nException: $e")
        }
    }

    @Test
    fun `Symmetry - Serialized packets always deserialize back to original`() {
        repeat(1000) {
            val original = generateRandomValidPacket()
            val bytes = original.toBytes()

            // For control packets, we must pass isControlChar=true
            val isControl = original is Packet.Control
            val parsed = Packet.fromBytes(bytes, isControlChar = isControl)

            assertEquals(
                "Failed to deserialize packet correctly on iteration $it",
                original,
                parsed
            )
        }
    }

    // --- Helpers ---

    private fun generateRandomValidPacket(): Packet {
        return if (Random.nextBoolean()) {
            // Generate Control Packet
            if (Random.nextBoolean()) {
                // Heartbeat
                Packet.Control.Heartbeat(
                    rootNodeId = Random.nextUInt(),
                    seq = Random.nextInt(),
                    hops = Random.nextInt(0, 255)
                )
            } else {
                // Raw / Handshake
                val op = Random.nextBytes(1)[0]
                // OpCode 0x10 is reserved for Heartbeat in parser logic, so avoid generating it blindly
                // to prevent "Symmetry" test confusion (Raw(0x10) -> Heartbeat).
                val safeOp = if (op == Protocol.OP_HEARTBEAT) 0x11.toByte() else op
                val payload = Random.nextBytes(Random.nextInt(0, 50))
                Packet.Control.Raw(safeOp, payload)
            }
        } else {
            // Generate Audio Packet
            Packet.Audio(Random.nextBytes(Random.nextInt(1, 100)))
        }
    }

    // Extension for UInt generation (available in newer Kotlin versions, manually added for safety)
    private fun Random.nextUInt(): UInt = nextInt().toUInt()
}