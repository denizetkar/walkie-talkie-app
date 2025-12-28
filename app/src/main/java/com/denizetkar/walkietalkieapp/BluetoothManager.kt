package com.denizetkar.walkietalkieapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.Charset
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredNode(
    val address: String,      // MAC Address
    val groupName: String,    // The Group they belong to
    val rssi: Int,            // Signal Strength
    val lastSeen: Long = System.currentTimeMillis()
)

class WalkieTalkieBluetoothManager(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = bluetoothManager.adapter

    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun scanGlobal(): Flow<List<DiscoveredNode>> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) { close(); return@callbackFlow }

        val foundNodes = ConcurrentHashMap<String, DiscoveredNode>()

        // Filter: Accept ALL groups
        val scanCallback = createScanCallback(foundNodes) { true }

        val pulseJob = launch { runScanPulseLoop(scanner, scanCallback) }
        val cleanupJob = launch { runCleanupLoop(foundNodes) }

        awaitClose {
            pulseJob.cancel()
            cleanupJob.cancel()
            scanner.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun startNode(groupName: String): Flow<List<DiscoveredNode>> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) { close(); return@callbackFlow }

        val foundPeers = ConcurrentHashMap<String, DiscoveredNode>()

        startAdvertising(groupName)

        // Filter: Accept ONLY my group
        val scanCallback = createScanCallback(foundPeers) { discoveredName ->
            discoveredName == groupName
        }

        val pulseJob = launch { runScanPulseLoop(scanner, scanCallback) }
        val cleanupJob = launch { runCleanupLoop(foundPeers) }

        awaitClose {
            stopAdvertising()
            pulseJob.cancel()
            cleanupJob.cancel()
            scanner.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(groupName: String) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)
        val nameBytes = groupName.take(10).toByteArray(Charset.forName("UTF-8"))

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(pUuid, nameBytes)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BT", "Advertising Started: $groupName")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BT", "Advertising Failed: $errorCode")
            }
        }

        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiseCallback?.let {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(it)
        }
        advertiseCallback = null
    }

    private fun ProducerScope<List<DiscoveredNode>>.createScanCallback(
        foundNodes: ConcurrentHashMap<String, DiscoveredNode>,
        filterPredicate: (String) -> Boolean
    ): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { scanResult ->
                    val serviceData = scanResult.scanRecord?.serviceData ?: return
                    val dataBytes = serviceData[ParcelUuid(Config.APP_SERVICE_UUID)] ?: return

                    val discoveredGroupName = String(dataBytes, Charset.forName("UTF-8")).trim { it <= ' ' }
                    if (!filterPredicate(discoveredGroupName)) return@let

                    val deviceAddress = scanResult.device.address
                    foundNodes[deviceAddress] = DiscoveredNode(
                        address = deviceAddress,
                        groupName = discoveredGroupName,
                        rssi = scanResult.rssi
                    )
                    trySend(foundNodes.values.toList().sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BT", "Scan failed: $errorCode")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runScanPulseLoop(
        scanner: BluetoothLeScanner,
        callback: ScanCallback
    ) {
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(Config.APP_SERVICE_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        while (true) {
            try {
                scanner.startScan(listOf(filter), settings, callback)
            } catch (e: Exception) { Log.e("BT", "Scan Start Error", e) }

            delay(Config.SCAN_PERIOD)

            try {
                scanner.stopScan(callback)
            } catch (e: Exception) { Log.e("BT", "Scan Stop Error", e) }

            delay(Config.SCAN_PAUSE)
        }
    }

    private suspend fun ProducerScope<List<DiscoveredNode>>.runCleanupLoop(
        foundNodes: ConcurrentHashMap<String, DiscoveredNode>
    ) {
        while (true) {
            delay(Config.CLEANUP_PERIOD)
            val now = System.currentTimeMillis()
            val removed = foundNodes.values.removeIf { (now - it.lastSeen) > Config.PEER_TIMEOUT }
            if (removed) {
                trySend(foundNodes.values.toList().sortedByDescending { it.rssi })
            }
        }
    }
}
