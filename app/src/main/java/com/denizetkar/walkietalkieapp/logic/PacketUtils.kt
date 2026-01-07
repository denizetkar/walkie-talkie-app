package com.denizetkar.walkietalkieapp.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketUtils {
    // Message Types
    const val TYPE_AUTH_CHALLENGE: Byte = 0x01
    const val TYPE_AUTH_RESPONSE: Byte = 0x02
    const val TYPE_AUTH_RESULT: Byte = 0x03
    const val TYPE_CLIENT_HELLO: Byte = 0x04
    const val TYPE_HEARTBEAT: Byte = 0x10
    const val TYPE_PING: Byte = 0xA0.toByte()

    // Header: [Version(4bits)|Flags(4bits)] [Type]
    // We use Version 1 (0x10)
    private const val HEADER_BYTE_0: Byte = 0x10

    fun createControlPacket(type: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(2 + payload.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(HEADER_BYTE_0)
        buffer.put(type)
        buffer.put(payload)
        return buffer.array()
    }

    fun parseControlPacket(data: ByteArray): Pair<Byte, ByteArray>? {
        if (data.size < 2) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Read Version/Flags byte (advances buffer position)
        // We ignore the value for now as we only have Version 1
        buffer.get()

        val type = buffer.get()
        val payload = ByteArray(data.size - 2)
        buffer.get(payload)

        return Pair(type, payload)
    }

    // --- Specific Payload Helpers ---

    fun createHeartbeatPayload(networkId: UInt, seq: Int, hops: Int): ByteArray {
        val buffer = ByteBuffer.allocate(9)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(networkId.toInt())
        buffer.putInt(seq)
        buffer.put(hops.toByte())
        return buffer.array()
    }

    fun parseHeartbeatPayload(payload: ByteArray): Triple<UInt, Int, Int>? {
        if (payload.size < 9) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val netId = buffer.int.toUInt()
        val seq = buffer.int
        val hops = buffer.get().toInt()
        return Triple(netId, seq, hops)
    }
}