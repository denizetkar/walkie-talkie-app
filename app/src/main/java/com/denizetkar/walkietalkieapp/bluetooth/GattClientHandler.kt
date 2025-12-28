package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.logic.ProtocolUtils
import com.denizetkar.walkietalkieapp.network.TransportDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

// Events specific to a Client connection
sealed class ClientEvent {
    data class Connected(val device: BluetoothDevice) : ClientEvent()
    data class Authenticated(val device: BluetoothDevice) : ClientEvent()
    data class Disconnected(val device: BluetoothDevice) : ClientEvent()
    data class MessageReceived(val device: BluetoothDevice, val data: ByteArray, val type: TransportDataType) : ClientEvent()
    data class Error(val device: BluetoothDevice, val message: String) : ClientEvent()
}

class GattClientHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val targetDevice: BluetoothDevice,
    private val ownNodeId: Int,
    private val accessCode: String
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val operationQueue = BleOperationQueue()

    private val _clientEvents = MutableSharedFlow<ClientEvent>()
    val clientEvents: SharedFlow<ClientEvent> = _clientEvents

    @SuppressLint("MissingPermission")
    fun connect() {
        Log.d("GattClient", "Connecting to ${targetDevice.address}...")
        try {
            bluetoothGatt = targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, "Permission Missing")) }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        operationQueue.clear()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) { /* Ignore */ }
        bluetoothGatt = null
    }

    // Unified Send Method
    fun sendMessage(type: TransportDataType, data: ByteArray) {
        val (uuid, writeType) = when (type) {
            TransportDataType.AUDIO -> Pair(
                GattServerHandler.CHAR_AUDIO_UUID,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            TransportDataType.CONTROL -> Pair(
                GattServerHandler.CHAR_CONTROL_UUID,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        }
        queueWrite(uuid, data, writeType)
    }

    private fun queueWrite(uuid: UUID, data: ByteArray, writeType: Int) {
        operationQueue.enqueue {
            writeCharacteristicInternal(uuid, data, writeType)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattClient", "Connection Error: $status")
                scope.launch { _clientEvents.emit(ClientEvent.Error(targetDevice, "Connection Error: $status")) }
                disconnect()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GattClient", "Connected. Requesting MTU...")
                scope.launch { _clientEvents.emit(ClientEvent.Connected(targetDevice)) }
                if (!gatt.requestMtu(Config.BLE_MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GattClient", "Disconnected from ${targetDevice.address}")
                scope.launch { _clientEvents.emit(ClientEvent.Disconnected(targetDevice)) }
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("GattClient", "MTU Changed: $mtu. Discovering Services...")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(Config.APP_SERVICE_UUID)
                if (service != null) {
                    Log.d("GattClient", "Services Discovered. Queueing Subscriptions...")
                    operationQueue.enqueue { enableNotificationInternal(gatt, service, GattServerHandler.CHAR_CHALLENGE_UUID) }
                    operationQueue.enqueue { enableNotificationInternal(gatt, service, GattServerHandler.CHAR_CONTROL_UUID) }
                    operationQueue.enqueue { enableNotificationInternal(gatt, service, GattServerHandler.CHAR_AUDIO_UUID) }
                } else {
                    Log.e("GattClient", "Target does not have the WalkieTalkie Service!")
                    disconnect()
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            operationQueue.operationCompleted()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            operationQueue.operationCompleted()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleIncomingData(characteristic.uuid, characteristic.value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            GattServerHandler.CHAR_CHALLENGE_UUID -> {
                val nonce = String(data, Charsets.UTF_8)
                solveChallenge(nonce)
            }
            GattServerHandler.CHAR_CONTROL_UUID -> {
                if (data.contentEquals(ProtocolUtils.HANDSHAKE_SUCCESS_PAYLOAD)) {
                    Log.d("GattClient", "Received Handshake Success ACK")
                    scope.launch { _clientEvents.emit(ClientEvent.Authenticated(targetDevice)) }
                    return
                }

                scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.CONTROL)) }
            }
            GattServerHandler.CHAR_AUDIO_UUID -> {
                scope.launch { _clientEvents.emit(ClientEvent.MessageReceived(targetDevice, data, TransportDataType.AUDIO)) }
            }
        }
    }

    private fun solveChallenge(nonce: String) {
        val responsePayload = ProtocolUtils.generateHandshakeResponse(accessCode, nonce, ownNodeId)

        Log.d("GattClient", "Sending Challenge Response...")
        queueWrite(GattServerHandler.CHAR_RESPONSE_UUID, responsePayload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotificationInternal(gatt: BluetoothGatt, service: BluetoothGattService, charUUID: UUID) {
        val characteristic = service.getCharacteristic(charUUID) ?: run {
            operationQueue.operationCompleted()
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(GattServerHandler.CCCD_UUID)
        if (descriptor == null) {
            operationQueue.operationCompleted()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicInternal(uuid: UUID, data: ByteArray, writeType: Int) {
        val service = bluetoothGatt?.getService(Config.APP_SERVICE_UUID) ?: run {
            operationQueue.operationCompleted()
            return
        }
        val char = service.getCharacteristic(uuid) ?: run {
            operationQueue.operationCompleted()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(char)
        }
    }
}