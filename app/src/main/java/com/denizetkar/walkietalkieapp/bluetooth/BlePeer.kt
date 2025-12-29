package com.denizetkar.walkietalkieapp.bluetooth

import com.denizetkar.walkietalkieapp.network.TransportDataType

/**
 * Represents a fully authenticated connection to another node.
 * Hides the complexity of whether it's a GATT Client or GATT Server connection.
 */
class BlePeer(
    val nodeId: Int,
    val address: String,
    private val sendImpl: suspend (ByteArray, TransportDataType) -> Unit,
    private val disconnectImpl: suspend () -> Unit
) {
    suspend fun send(data: ByteArray, type: TransportDataType) {
        sendImpl(data, type)
    }

    suspend fun disconnect() {
        disconnectImpl()
    }
}