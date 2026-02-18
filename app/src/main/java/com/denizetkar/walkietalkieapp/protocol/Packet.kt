package com.denizetkar.walkietalkieapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol v2.0 Definitions.
 * Optimized for BLE MTUs, but includes a Version header for future-proofing.
 */
object Protocol {
    // Header: [Version(4bits) | Reserved(4bits)]
    // Version 1 = 0x10
    const val HEADER_V1: Byte = 0x10

    // OpCodes for Control Packets
    const val OP_HELLO: Byte = 0x01          // Client -> Server
    const val OP_AUTH_CHALLENGE: Byte = 0x02 // Server -> Client
    const val OP_AUTH_RESPONSE: Byte = 0x03  // Client -> Server
    const val OP_AUTH_RESULT: Byte = 0x04    // Server -> Client
    const val OP_HEARTBEAT: Byte = 0x10      // Bi-directional
}

sealed class Packet {
    abstract fun toBytes(): ByteArray

    // --- Control Packets (Reliable / Ordered) ---
    // Structure: [Header(1)] [OpCode(1)] [Payload(N)]
    sealed class Control(val opCode: Byte) : Packet() {
        override fun toBytes(): ByteArray {
            val payload = encodePayload()
            val buffer = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(Protocol.HEADER_V1)
            buffer.put(opCode)
            buffer.put(payload)
            return buffer.array()
        }

        protected abstract fun encodePayload(): ByteArray

        // 1. Heartbeat (The Pulse of the Mesh)
        data class Heartbeat(
            val netId: UInt,
            val seq: Int,
            val hops: Int
        ) : Control(Protocol.OP_HEARTBEAT) {
            override fun encodePayload(): ByteArray {
                // Payload: [NetId(4)] [Seq(4)] [Hops(1)]
                val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(netId.toInt())
                buf.putInt(seq)
                buf.put(hops.toByte())
                return buf.array()
            }
        }

        // 2. Raw Wrapper (For Handshake Packets)
        data class Raw(val op: Byte, val data: ByteArray) : Control(op) {
            override fun encodePayload(): ByteArray = data

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Raw
                if (op != other.op) return false
                if (!data.contentEquals(other.data)) return false
                return true
            }
            override fun hashCode(): Int = 31 * op + data.contentHashCode()
        }
    }

    // --- Audio Packets (Unreliable / Unordered) ---
    // Structure: [Raw Opus Data] (No Header overhead)
    data class Audio(val data: ByteArray) : Packet() {
        override fun toBytes(): ByteArray = data

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Audio
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }

    companion object {
        fun fromBytes(data: ByteArray, isControlChar: Boolean): Packet? {
            if (data.isEmpty()) return null

            // Audio packets have no header, identified by Characteristic UUID context
            if (!isControlChar) {
                return Audio(data)
            }

            // Control Packet Parsing
            if (data.size < 2) return null
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val header = buffer.get()
            // Version Check (Mask out reserved bits)
            if ((header.toInt() and 0xF0) != 0x10) return null

            val op = buffer.get()
            val payload = ByteArray(data.size - 2)
            buffer.get(payload)

            return when (op) {
                Protocol.OP_HEARTBEAT -> {
                    if (payload.size < 9) return null
                    try {
                        val pBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        val netId = pBuf.int.toUInt()
                        val seq = pBuf.int
                        val hops = pBuf.get().toInt() and 0xFF
                        Control.Heartbeat(netId, seq, hops)
                    } catch (_: Exception) { null }
                }
                else -> Control.Raw(op, payload)
            }
        }
    }
}