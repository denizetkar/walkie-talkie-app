package com.denizetkar.walkietalkieapp.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a generic node found during discovery.
 * We don't care if it's BLE MAC or IP Address here.
 */
data class TransportNode(
    val id: String,           // Unique ID (MAC address or IP)
    val name: String?,        // "Hiking Group"
    val extraInfo: Map<String, Any> // RSSI, Availability, NodeID, etc.
)

/**
 * Events coming FROM the hardware layer TO the app.
 */
sealed class TransportEvent {
    data class NodeDiscovered(val node: TransportNode) : TransportEvent()
    data class ConnectionEstablished(val address: String) : TransportEvent()
    data class ConnectionLost(val address: String) : TransportEvent()
    data class DataReceived(val fromAddress: String, val data: ByteArray, val type: TransportDataType) : TransportEvent()
    data class Error(val message: String) : TransportEvent()
}

/**
 * The Contract for any physical layer (BLE, WiFi, etc).
 */
interface NetworkTransport {
    // The stream of events (Discovery, Connections, Data)
    val events: Flow<TransportEvent>

    // Expose real-time hardware state
    val isScanning: StateFlow<Boolean>

    fun setCredentials(accessCode: String, ownNodeId: Int)

    suspend fun startDiscovery()
    suspend fun stopDiscovery()

    suspend fun startAdvertising(groupName: String)
    suspend fun stopAdvertising()

    // Connection Management
    suspend fun connect(address: String)
    suspend fun disconnect(address: String)

    // Data Transfer
    suspend fun send(toAddress: String, data: ByteArray, type: TransportDataType)
    suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeAddress: String? = null)
}