package com.denizetkar.walkietalkieapp.logic

import java.nio.ByteBuffer
import java.security.MessageDigest

object ProtocolUtils {

    /**
     * Generates the response payload for the Challenge-Response handshake.
     * Payload = [First 16 bytes of SHA256(AccessCode + Nonce)] + [NodeID (4 bytes)]
     */
    fun generateHandshakeResponse(accessCode: String, nonce: String, ownNodeId: Int): ByteArray {
        val input = accessCode + nonce
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hashBytes = fullHash.copyOfRange(0, 16)
        val nodeIdBytes = ByteBuffer.allocate(4).putInt(ownNodeId).array()
        return hashBytes + nodeIdBytes
    }

    /**
     * Verifies a received handshake payload.
     */
    fun verifyHandshake(
        payload: ByteArray,
        accessCode: String,
        nonce: String
    ): Int? {
        if (payload.size != 20) return null

        val receivedHash = payload.copyOfRange(0, 16)
        val nodeIdBytes = payload.copyOfRange(16, 20)
        val nodeId = ByteBuffer.wrap(nodeIdBytes).int

        val input = accessCode + nonce
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val expectedHash = fullHash.copyOfRange(0, 16)

        return if (receivedHash.contentEquals(expectedHash)) {
            nodeId
        } else {
            null
        }
    }
}