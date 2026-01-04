package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    val APP_SERVICE_UUID: UUID = UUID.fromString("3d8a635b-07b0-4892-bf5f-e1f47eaf0291")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
    val CHAR_DATA_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")

    // --- NEW: Audio Configuration ---
    // 48kHz is the native sample rate for most Android devices (avoids resampling)
    const val AUDIO_SAMPLE_RATE = 48000

    // 60ms Frame = ~16 packets/sec.
    // This is much friendlier to BLE than the previous 40ms (25 packets/sec).
    const val AUDIO_FRAME_SIZE_MS = 60

    // Max depth of the jitter buffer before we drop packets to catch up.
    const val AUDIO_JITTER_BUFFER_MS = 500

    // Mesh Topology Constraints
    const val TARGET_PEERS = 3
    const val MAX_PEERS = 5

    // BLE Technical Limits
    const val BLE_MTU = 512
    const val MAX_ADVERTISING_NAME_BYTES = 20
    const val WORST_RSSI = -100

    // Timing
    const val SCAN_PERIOD_AGGRESSIVE = 4000L
    const val SCAN_PAUSE_AGGRESSIVE = 4000L
    const val SCAN_PERIOD_LAZY = 2000L
    const val SCAN_INTERVAL_LAZY = 10000L

    const val HEARTBEAT_INTERVAL = 1000L

    const val CLEANUP_PERIOD = 2000L
    const val PACKET_CACHE_TIMEOUT = 4000L
    const val GROUP_ADVERTISEMENT_TIMEOUT = 4000L
    const val GROUP_JOIN_TIMEOUT = 15000L
    const val PEER_CONNECT_TIMEOUT = 5000L
    const val BLE_OPERATION_TIMEOUT = 3000L
    const val HEARTBEAT_TIMEOUT = 4000L
}