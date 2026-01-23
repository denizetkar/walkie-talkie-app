package com.denizetkar.walkietalkieapp.protocol

import com.denizetkar.walkietalkieapp.Config
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Encapsulates the Zone A (Bouncer) authentication logic.
 * Used by GattServer and GattClient to verify peers before exposing them to the Core.
 * Pure functions only.
 */
object HandshakeLogic {
    private const val HASH_SIZE = Config.PROTOCOL_HASH_SIZE // 12 bytes

    fun generateResponse(accessCode: String, nonce: String, ownNodeId: UInt): ByteArray {
        val input = accessCode + nonce + ownNodeId.toString()
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(
            StandardCharsets.UTF_8))

        // Protocol: [Hash(12)] [NodeID(4)]
        val hashBytes = fullHash.copyOfRange(0, HASH_SIZE)
        val nodeIdBytes = ByteBuffer.allocate(4).putInt(ownNodeId.toInt()).array()
        return hashBytes + nodeIdBytes
    }

    fun verifyResponse(payload: ByteArray, accessCode: String, nonce: String): UInt? {
        if (payload.size != HASH_SIZE + 4) return null

        val receivedHash = payload.copyOfRange(0, HASH_SIZE)
        val nodeIdBytes = payload.copyOfRange(HASH_SIZE, HASH_SIZE + 4)
        val nodeId = ByteBuffer.wrap(nodeIdBytes).int.toUInt()

        val input = accessCode + nonce + nodeId.toString()
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(
            StandardCharsets.UTF_8))
        val expectedHash = fullHash.copyOfRange(0, HASH_SIZE)

        return if (receivedHash.contentEquals(expectedHash)) {
            nodeId
        } else {
            null
        }
    }

    fun truncateUtf8(input: String, maxBytes: Int): ByteArray {
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        return if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
    }
}