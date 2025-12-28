package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    // The Unique App ID
    val APP_SERVICE_UUID: UUID = UUID.fromString("b5e764d4-4a06-4c96-8c25-f378ccf9c8e1")

    // Mesh Topology Constraints
    const val TARGET_PEERS = 3
    const val MAX_PEERS = 5

    // BLE Technical Limits
    const val BLE_MTU = 512 // Request high MTU for Opus packets
    const val MAX_ADVERTISING_NAME_BYTES = 20 // Leave room for flags + headers (31 byte limit)
    const val WORST_RSSI = -100

    // Timing
    const val SCAN_PERIOD_AGGRESSIVE = 10000L
    const val SCAN_PAUSE_AGGRESSIVE = 500L
    const val SCAN_PERIOD_LAZY = 5000L
    const val SCAN_INTERVAL_LAZY = 30000L

    const val CLEANUP_PERIOD = 2000L
    const val PACKET_CACHE_TIMEOUT = 4000L
    const val GROUP_ADVERTISEMENT_TIMEOUT = 4000L
    const val GROUP_JOIN_TIMEOUT = 8000L
}