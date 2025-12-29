package com.denizetkar.walkietalkieapp.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class TransportNode(
    val id: String,           // Unique ID (MAC address or IP)
    val name: String?,        // "Hiking Group"
    val extraInfo: Map<String, Any> // RSSI, Availability, NodeID, etc.
)

sealed class TransportEvent {
    data class NodeDiscovered(val node: TransportNode) : TransportEvent()
    data class ConnectionEstablished(val nodeId: Int) : TransportEvent()
    data class ConnectionLost(val nodeId: Int) : TransportEvent()
    data class DataReceived(val fromNodeId: Int, val data: ByteArray, val type: TransportDataType) : TransportEvent()
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

    suspend fun connect(address: String, nodeId: Int)
    suspend fun disconnect(nodeId: Int)

    suspend fun send(toNodeId: Int, data: ByteArray, type: TransportDataType)
    suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeNodeId: Int? = null)
}