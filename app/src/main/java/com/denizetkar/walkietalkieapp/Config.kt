package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    // --- Identifiers ---
    val APP_SERVICE_UUID: UUID = UUID.fromString("3d8a635b-07b0-4892-bf5f-e1f47eaf0291")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
    val CHAR_DATA_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")

    // 0xFFFF is the "Testing" ID. Real apps should register with Bluetooth SIG.
    const val BLE_MANUFACTURER_ID = 0xFFFF

    // --- Audio Configuration ---
    // 48kHz is the native sample rate for most Android devices (avoids resampling)
    const val AUDIO_SAMPLE_RATE = 48000

    // 60ms Frame = ~16 packets/sec.
    // This is much friendlier to BLE than the previous 40ms (25 packets/sec).
    const val AUDIO_FRAME_SIZE_MS = 60

    // Max depth of the jitter buffer before we drop packets to catch up.
    const val AUDIO_JITTER_BUFFER_MS = 1000

    // --- Protocol Layouts (Byte Offsets & Sizes) ---
    // Service Data: [NodeID(4)] [NetworkID(4)] [Hops(1)] [Avail(1)]
    const val PACKET_SERVICE_DATA_SIZE = 10

    // Handshake Hash Size (Truncated SHA-256 to fit in small packets)
    const val PROTOCOL_HASH_SIZE = 12

    // --- Buffer Sizes & Limits ---
    const val MAX_AUDIO_QUEUE_CAPACITY = 5
    const val AUDIO_STARVATION_THRESHOLD = 4
    const val EVENT_FLOW_BUFFER_CAPACITY = 256

    // Mesh Topology Constraints
    const val TARGET_PEERS = 3
    const val MAX_PEERS = 5

    // --- BLE Technical Limits ---
    const val BLE_DEFAULT_MTU = 23
    const val BLE_MTU = 512
    const val MAX_ADVERTISING_NAME_BYTES = 20
    const val WORST_RSSI = -100

    // --- Tuning / Timeouts ---
    // Retry count for reliable Control Packets (GATT writes)
    const val GATT_RETRY_ATTEMPTS = 3

    // Artificial delay to allow Android GATT stack to stabilize after service discovery
    const val GATT_SUBSCRIPTION_DELAY = 300L

    const val HEARTBEAT_INTERVAL = 1000L

    const val CLEANUP_PERIOD = 2000L
    const val PACKET_CACHE_TIMEOUT = 4000L
    const val GROUP_ADVERTISEMENT_TIMEOUT = 5000L
    const val GROUP_JOIN_TIMEOUT = 15000L
    const val PEER_CONNECT_TIMEOUT = 6000L
    const val BLE_OPERATION_TIMEOUT = 3000L
    const val HEARTBEAT_TIMEOUT = 3000L
}