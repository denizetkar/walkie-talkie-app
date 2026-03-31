package com.denizetkar.walkietalkieapp.protocol

import org.junit.Assert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {

    @Test
    fun `Heartbeat Packet Serialization Round Trip`() {
        val original = Packet.Control.Heartbeat(
            rootNodeId = 123456u,
            seq = 99,
            hops = 2
        )

        val bytes = original.toBytes()

        // Verify header (Version 0x10, OpCode 0x10)
        Assert.assertEquals(0x10.toByte(), bytes[0])
        Assert.assertEquals(Protocol.OP_HEARTBEAT, bytes[1])

        val parsed = Packet.fromBytes(bytes, isControlChar = true)

        assertTrue(parsed is Packet.Control.Heartbeat)
        Assert.assertEquals(original, parsed)
    }

    @Test
    fun `Handshake - Response Verification`() {
        val accessCode = "9999"
        val nonce = "abcdefgh"
        val myNodeId = 100u

        // Client Generates
        val response = HandshakeLogic.generateResponse(accessCode, nonce, myNodeId)
        Assert.assertEquals(16, response.size) // 12 hash + 4 ID

        // Server Verifies (Correct Code)
        val resultSuccess = HandshakeLogic.verifyResponse(response, accessCode, nonce)
        Assert.assertEquals(myNodeId, resultSuccess)

        // Server Verifies (Wrong Code)
        val resultFail = HandshakeLogic.verifyResponse(response, "0000", nonce)
        assertNull(resultFail)
    }

    @Test
    fun `Parsing - Returns null on empty or truncated header`() {
        // Empty
        assertNull(Packet.fromBytes(byteArrayOf(), true))

        // 1 Byte (Header only, no OpCode)
        assertNull(Packet.fromBytes(byteArrayOf(0x10), true))

        // 2 Bytes (Header + OpCode, but OpCode requires payload?)
        // Actually, 2 bytes is valid for a raw packet with empty payload.
        val validEmpty = Packet.fromBytes(byteArrayOf(0x10, 0x99.toByte()), true)
        assertTrue(validEmpty is Packet.Control.Raw)
    }

    @Test
    fun `Parsing - Validates Protocol Version`() {
        // Version 2 (0x20) should be rejected
        val badVersion = byteArrayOf(0x20, Protocol.OP_HEARTBEAT)
        assertNull(Packet.fromBytes(badVersion, true))
    }

    @Test
    fun `Parsing - Handles Truncated Heartbeat Gracefully`() {
        // Heartbeat requires 9 bytes of payload. We send 2.
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x10) // Version
        buffer.put(Protocol.OP_HEARTBEAT)
        buffer.putShort(123) // Incomplete Payload

        // Should return NULL, not throw BufferUnderflowException
        val result = Packet.fromBytes(buffer.array(), true)
        assertNull(result)
    }

    @Test
    fun `Parsing - Audio Packets have no header validation`() {
        // Audio packets are just raw bytes.
        val raw = byteArrayOf(0x01, 0x02, 0x03)
        val packet = Packet.fromBytes(raw, isControlChar = false)

        assertTrue(packet is Packet.Audio)
        assertArrayEquals(raw, (packet as Packet.Audio).data)
    }
}