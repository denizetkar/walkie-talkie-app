package com.denizetkar.walkietalkieapp.logic

import java.nio.ByteBuffer
import java.security.MessageDigest

object ProtocolUtils {
    // CHANGED: Reduced hash size to 12 bytes to fit in default MTU (23)
    // 2 (Header) + 12 (Hash) + 4 (NodeID) = 18 bytes < 20 bytes limit
    private const val HASH_SIZE = 12

    fun generateHandshakeResponse(accessCode: String, nonce: String, ownNodeId: UInt): ByteArray {
        val input = accessCode + nonce + ownNodeId.toString()
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hashBytes = fullHash.copyOfRange(0, HASH_SIZE)
        val nodeIdBytes = ByteBuffer.allocate(4).putInt(ownNodeId.toInt()).array()
        return hashBytes + nodeIdBytes
    }

    fun verifyHandshake(
        payload: ByteArray,
        accessCode: String,
        nonce: String
    ): UInt? {
        if (payload.size != HASH_SIZE + 4) return null

        val receivedHash = payload.copyOfRange(0, HASH_SIZE)
        val nodeIdBytes = payload.copyOfRange(HASH_SIZE, HASH_SIZE + 4)
        val nodeId = ByteBuffer.wrap(nodeIdBytes).int.toUInt()

        val input = accessCode + nonce + nodeId.toString()
        val fullHash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val expectedHash = fullHash.copyOfRange(0, HASH_SIZE)

        return if (receivedHash.contentEquals(expectedHash)) {
            nodeId
        } else {
            null
        }
    }

    fun truncateUtf8(input: String, maxBytes: Int): ByteArray {
        var byteCount = 0
        val sb = StringBuilder()
        for (char in input) {
            val charBytes = char.toString().toByteArray(Charsets.UTF_8)
            if (byteCount + charBytes.size > maxBytes) break
            byteCount += charBytes.size
            sb.append(char)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}