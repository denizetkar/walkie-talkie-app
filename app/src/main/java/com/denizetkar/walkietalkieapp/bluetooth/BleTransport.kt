package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.NetworkTransport
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.network.TransportEvent
import com.denizetkar.walkietalkieapp.network.TransportNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.copyOfRange
import kotlin.collections.forEach

class BleTransport(
    context: Context,
    private val scope: CoroutineScope
) : NetworkTransport {

    private val context = context.applicationContext
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private val scanner = adapter.bluetoothLeScanner
    private val advertiser = adapter.bluetoothLeAdvertiser

    private val _events = MutableSharedFlow<TransportEvent>()
    override val events = _events.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning = _isScanning.asStateFlow()

    private var myAccessCode: String = ""
    private var myNodeId: Int = 0

    private val serverHandler = GattServerHandler(context, scope)
    private val activeClients = ConcurrentHashMap<String, GattClientHandler>()
    private val clientJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            serverHandler.serverEvents.collect { event ->
                when (event) {
                    is ServerEvent.ClientAuthenticated -> {
                        _events.emit(TransportEvent.ConnectionEstablished(event.device.address))
                    }
                    is ServerEvent.ClientDisconnected -> {
                        _events.emit(TransportEvent.ConnectionLost(event.device.address))
                    }
                    is ServerEvent.MessageReceived -> {
                        _events.emit(TransportEvent.DataReceived(event.device.address, event.data, event.type))
                    }
                    else -> {}
                }
            }
        }
    }

    override fun setCredentials(accessCode: String, ownNodeId: Int) {
        this.myAccessCode = accessCode
        this.myNodeId = ownNodeId
        serverHandler.currentAccessCode = accessCode
    }

    // ===========================================================================
    // DISCOVERY
    // ===========================================================================

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        if (scanCallback != null) return

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(Config.APP_SERVICE_UUID), null)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { processScanResult(it) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e("BleTransport", "Scan Failed: $errorCode")
                _isScanning.value = false
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            Log.d("BleTransport", "Discovery Started")
        } catch (e: Exception) {
            _isScanning.value = false
            Log.e("BleTransport", "Start Scan Error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val device = result.device
        val rssi = result.rssi

        val serviceData = record.serviceData?.get(ParcelUuid(Config.APP_SERVICE_UUID)) ?: return
        // Expected Format: [NodeID (4)] + [Availability (1)] + [GroupHash (1 - optional)]
        if (serviceData.size < 5) return

        val nodeId = ByteBuffer.wrap(serviceData.copyOfRange(0, 4)).int
        val availabilityByte = serviceData[4].toInt()
        val isAvailable = (availabilityByte == 1)

        val manufacturerBytes = record.getManufacturerSpecificData(0xFFFF)
        val groupName = if (manufacturerBytes != null) {
            String(manufacturerBytes, Charsets.UTF_8)
        } else {
            "Unknown Group"
        }

        val node = TransportNode(
            id = device.address,
            name = groupName,
            extraInfo = mapOf("rssi" to rssi, "nodeId" to nodeId, "available" to isAvailable)
        )
        scope.launch { _events.emit(TransportEvent.NodeDiscovered(node)) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        scanCallback?.let {
            try { scanner.stopScan(it) }
            catch (e: Exception) { Log.e("BleTransport", "Stop Scan Error", e) }
        }
        scanCallback = null
        _isScanning.value = false
        Log.d("BleTransport", "Discovery Stopped")
    }

    // ===========================================================================
    // ADVERTISING
    // ===========================================================================

    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertising(groupName: String) {
        if (advertiseCallback != null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)
        val payload = ByteBuffer.allocate(5)
        payload.putInt(myNodeId)
        payload.put(1.toByte()) // 1 = Available. (Logic will update this later)

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(pUuid, payload.array())
            .build()

        val nameBytes = groupName.toByteArray(Charsets.UTF_8).take(Config.MAX_ADVERTISING_NAME_BYTES).toByteArray()
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, nameBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BleTransport", "Advertising Started: $groupName")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BleTransport", "Advertising Failed: $errorCode")
            }
        }

        try {
            advertiser.startAdvertising(settings, mainData, scanResponseData, advertiseCallback)
            serverHandler.startServer()
        } catch (e: Exception) {
            Log.e("BleTransport", "Start Advertising Error", e)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopAdvertising() {
        advertiseCallback?.let {
            try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
        }
        advertiseCallback = null
        serverHandler.stopServer()
    }

    // ===========================================================================
    // CONNECTION & DATA
    // ===========================================================================

    private val connectMutex = Mutex()
    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String) = connectMutex.withLock {
        if (activeClients.containsKey(address)) return

        val device = adapter.getRemoteDevice(address)
        val client = GattClientHandler(context, scope, device, myNodeId, myAccessCode)

        activeClients[address] = client

        val job = scope.launch {
            client.clientEvents.collect { event ->
                when (event) {
                    is ClientEvent.Authenticated -> {
                        _events.emit(TransportEvent.ConnectionEstablished(address))
                    }
                    is ClientEvent.Disconnected -> {
                        _events.emit(TransportEvent.ConnectionLost(address))
                        disconnect(address)
                    }
                    is ClientEvent.MessageReceived -> {
                        _events.emit(TransportEvent.DataReceived(address, event.data, event.type))
                    }
                    is ClientEvent.Error -> {
                        Log.e("BleTransport", "Client Error: ${event.message}")
                        disconnect(address)
                    }
                    else -> {}
                }
            }
        }
        clientJobs[address] = job

        client.connect()
    }

    override suspend fun disconnect(address: String) {
        clientJobs[address]?.cancel()
        clientJobs.remove(address)
        activeClients[address]?.disconnect()
        activeClients.remove(address)
        serverHandler.disconnect(address)
    }

    override suspend fun send(toAddress: String, data: ByteArray, type: TransportDataType) {
        // 1. Try Client
        val client = activeClients[toAddress]
        if (client != null) {
            client.sendMessage(type, data)
            return
        }

        // 2. Try Server
        serverHandler.sendTo(toAddress, data, type)
    }

    override suspend fun sendToAll(data: ByteArray, type: TransportDataType, excludeAddress: String?) {
        // 1. Send to all Clients we initiated (excluding the sender)
        activeClients.forEach { (address, client) ->
            if (address != excludeAddress) {
                client.sendMessage(type, data)
            }
        }

        // 2. Send to all Devices connected to our Server (excluding the sender)
        serverHandler.broadcastToClients(data, excludeAddress, type)
    }
}