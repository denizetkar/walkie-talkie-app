package com.denizetkar.walkietalkieapp.protocol

import org.junit.Assert
import org.junit.Test

class ProtocolTest {

    @Test
    fun `Heartbeat Packet Serialization Round Trip`() {
        val original = Packet.Control.Heartbeat(
            netId = 123456u,
            seq = 99,
            hops = 2
        )

        val bytes = original.toBytes()

        // Verify header (Version 0x10, OpCode 0x10)
        Assert.assertEquals(0x10.toByte(), bytes[0])
        Assert.assertEquals(Protocol.OP_HEARTBEAT, bytes[1])

        val parsed = Packet.fromBytes(bytes, isControlChar = true)

        Assert.assertTrue(parsed is Packet.Control.Heartbeat)
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
        Assert.assertNull(resultFail)
    }
}