package com.denizetkar.walkietalkieapp.network

enum class TransportDataType {
    CONTROL, // Reliable, for Handshakes/Routing (Write with Response)
    AUDIO    // Fast, for Voice (Write without Response)
}