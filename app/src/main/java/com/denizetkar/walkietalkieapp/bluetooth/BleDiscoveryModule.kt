package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportEvent
import com.denizetkar.walkietalkieapp.network.TransportNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleDiscoveryModule(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<TransportEvent>,
    private val isScanning: MutableStateFlow<Boolean>
) {
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanCallback != null) return

        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(Config.APP_SERVICE_UUID), null)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { processScanResult(it) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e("BleDiscovery", "Scan Failed: $errorCode")
                isScanning.value = false
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning.value = true
            Log.d("BleDiscovery", "Discovery Started")
        } catch (e: Exception) {
            isScanning.value = false
            Log.e("BleDiscovery", "Start Scan Error", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val scanner = adapter.bluetoothLeScanner ?: return
        scanCallback?.let {
            try { scanner.stopScan(it) }
            catch (e: Exception) { Log.e("BleDiscovery", "Stop Scan Error", e) }
        }
        scanCallback = null
        isScanning.value = false
        Log.d("BleDiscovery", "Discovery Stopped")
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val device = result.device
        val rssi = result.rssi

        val serviceData = record.serviceData?.get(ParcelUuid(Config.APP_SERVICE_UUID)) ?: return

        // Expected: [NodeID(4)] [NetworkID(4)] [Hops(1)] [Avail(1)] = 10 bytes
        if (serviceData.size < 10) return

        val buffer = ByteBuffer.wrap(serviceData).order(ByteOrder.LITTLE_ENDIAN)
        val nodeId = buffer.int
        val networkId = buffer.int
        val hops = buffer.get().toInt()
        val availabilityByte = buffer.get().toInt()
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
            rssi = rssi,
            nodeId = nodeId,
            networkId = networkId,
            hopsToRoot = hops,
            isAvailable = isAvailable
        )
        scope.launch { events.emit(TransportEvent.NodeDiscovered(node)) }
    }
}