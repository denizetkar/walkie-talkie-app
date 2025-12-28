package com.denizetkar.walkietalkieapp

import java.util.UUID

object Config {
    // The Unique App ID
    val APP_SERVICE_UUID: UUID = UUID.fromString("b5e764d4-4a06-4c96-8c25-f378ccf9c8e1")

    // Scanning
    const val SCAN_PERIOD = 10000L
    const val SCAN_PAUSE = 500L

    // Cleanup & Timeouts
    const val CLEANUP_PERIOD = 2000L
    const val PEER_TIMEOUT = 4000L
}