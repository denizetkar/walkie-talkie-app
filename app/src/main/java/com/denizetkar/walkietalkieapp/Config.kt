package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    // --- Identifiers ---
    val APP_SERVICE_UUID: UUID = UUID.fromString("3d8a635b-07b0-4892-bf5f-e1f47eaf0291")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
    val CHAR_DATA_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")

    // 0xFFFF is the "Testing" ID.
    const val BLE_MANUFACTURER_ID = 0xFFFF

    // --- Audio Configuration ---
    // 48kHz is the native sample rate for most Android devices (avoids resampling)
    const val AUDIO_SAMPLE_RATE = 48000
    // 60ms Frame = ~16 packets/sec. Ideal for BLE throughput/latency balance.
    const val AUDIO_FRAME_SIZE_MS = 60
    // Max depth of the jitter buffer before we drop packets to catch up.
    const val AUDIO_JITTER_BUFFER_MS = 1000
    // Time to wait in between consecutive audio session start attempts (Retry Loop)
    const val AUDIO_SESSION_START_DELAY = 1000L

    // --- Protocol Limits ---
    const val PACKET_SERVICE_DATA_SIZE = 10
    // Handshake Hash Size (Truncated SHA-256 to fit in small packets)
    const val PROTOCOL_HASH_SIZE = 12

    // --- BLE Technical Limits ---
    const val BLE_DEFAULT_MTU = 23
    const val BLE_MTU_TARGET = 512
    // STRICT: We ACCEPT anything down to 300 bytes.
    // 60ms Opus packet ~ 240 bytes.
    const val BLE_MTU_MIN = 300
    const val MAX_ADVERTISING_NAME_BYTES = 20

    // --- Buffer Sizes & Limits ---
    const val MAX_AUDIO_QUEUE_CAPACITY = 8
    const val AUDIO_STARVATION_THRESHOLD = 4

    // --- Mesh Topology Constraints ---
    const val TARGET_PEERS = 3
    const val MAX_PEERS = 5

    // --- Timeouts & Tuning ---
    const val SCAN_RETRY_ATTEMPTS = 6
    const val SCAN_RETRY_COOLDOWN = 1000L

    const val WAKE_LOCK_TIMEOUT = 4 * 60 * 60 * 1000L
    const val GATT_RETRY_ATTEMPTS = 3
    const val GATT_RETRY_COOLDOWN = 500L

    // Delay to allow Android GATT stack to stabilize after service discovery
    const val GATT_SUBSCRIPTION_DELAY = 300L

    // Frequency of Heartbeat broadcasts (only if Root)
    const val HEARTBEAT_INTERVAL = 1000L

    // Frequency of Cache Cleanup / Liveness checks
    const val CLEANUP_PERIOD = 2000L

    const val PACKET_CACHE_TIMEOUT = 5000L
    const val GROUP_ADVERTISEMENT_TIMEOUT = 6000L

    // Global limit for the entire "Join Group" operation (Core Logic)
    const val GROUP_JOIN_TIMEOUT = 15000L

    // --- SEPARATION OF CONCERNS ---

    // DRIVER: How long to wait for a single socket connection/handshake to finish.
    // Must be short enough to allow retries within GROUP_JOIN_TIMEOUT.
    const val BLE_CONNECT_TIMEOUT = 5000L

    // CORE: How long to wait for a packet from a peer before declaring them dead.
    // Should be slightly longer than BLE_CONNECT_TIMEOUT to tolerate temporary interference.
    const val PEER_LIVENESS_TIMEOUT = 7000L

    // If we haven't heard from Root in this time, we downgrade to Standalone.
    const val HEARTBEAT_TIMEOUT = 3000L

    const val BLE_OPERATION_TIMEOUT = 2000L

    const val PEER_DISCONNECT_TIMEOUT = 3000L
}