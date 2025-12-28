package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    // The Unique App ID
    val APP_SERVICE_UUID: UUID = UUID.fromString("b5e764d4-4a06-4c96-8c25-f378ccf9c8e1")

    // Mesh Topology Constraints
    const val TARGET_PEERS = 3   // The "Stable" state. Stop aggressive scanning here.
    const val MAX_PEERS = 5      // The "Burst" capacity. Keep slots open for merging islands.

    // Timing
    const val SCAN_PERIOD_AGGRESSIVE = 5000L  // Scan for 5s when lonely
    const val SCAN_PAUSE_AGGRESSIVE = 2000L   // Rest for 2s

    const val SCAN_PERIOD_LAZY = 5000L        // Scan for 5s
    const val SCAN_INTERVAL_LAZY = 30000L     // Every 30s (Heartbeat scan)

    // Cleanup & Timeouts
    const val CLEANUP_PERIOD = 2000L
    const val TIMEOUT = 4000L
}