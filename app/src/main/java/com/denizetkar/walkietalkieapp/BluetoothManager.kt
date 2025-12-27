package com.denizetkar.walkietalkieapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Simple data class for UI
data class DiscoveredGroup(
    val name: String,
    val macAddress: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis() // Default to Now
)

// A fixed UUID for our App Service so we only see our own devices
val APP_SERVICE_UUID: UUID = UUID.fromString("b5e764d4-4a06-4c96-8c25-f378ccf9c8e1")

class WalkieTalkieBluetoothManager(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = bluetoothManager.adapter

    // --- ADVERTISING (HOST) ---

    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun startHosting(groupName: String) {
        if (!adapter.isEnabled) return

        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val pUuid = ParcelUuid(APP_SERVICE_UUID)

        // FIX: Ensure name fits in the remaining ~10 bytes
        // 31 bytes total - 3 (Flags) - 2 (Header) - 16 (UUID) = 10 bytes max
        val nameBytes = groupName.take(10).toByteArray(Charset.forName("UTF-8"))

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(pUuid, nameBytes)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BT", "Advertising started successfully! Group: $groupName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BT", "Advertising failed with error code: $errorCode")
            }
        }

        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopHosting() {
        advertiseCallback?.let {
            adapter.bluetoothLeAdvertiser.stopAdvertising(it)
        }
        advertiseCallback = null
    }

    // --- SCANNING (PEER) ---

    @SuppressLint("MissingPermission")
    fun scanForGroups(): Flow<List<DiscoveredGroup>> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        // Shared state
        val foundGroups = java.util.concurrent.ConcurrentHashMap<String, DiscoveredGroup>()
        val scanCallback = createScanCallback(foundGroups)

        // Start background jobs
        val cleanupJob = launch { runCleanupLoop(foundGroups) }
        val pulseJob = launch { runScanPulseLoop(scanner, scanCallback) }

        awaitClose {
            cleanupJob.cancel()
            pulseJob.cancel()
            scanner.stopScan(scanCallback)
        }
    }

    private fun ProducerScope<List<DiscoveredGroup>>.createScanCallback(
        foundGroups: MutableMap<String, DiscoveredGroup>
    ): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { scanResult ->
                    val serviceData = scanResult.scanRecord?.serviceData ?: return
                    val dataBytes = serviceData[ParcelUuid(APP_SERVICE_UUID)] ?: return

                    val groupName = String(dataBytes, Charset.forName("UTF-8")).trim { it <= ' ' }
                    val deviceAddress = scanResult.device.address

                    foundGroups[deviceAddress] = DiscoveredGroup(
                        name = groupName,
                        macAddress = deviceAddress,
                        rssi = scanResult.rssi
                    )
                    trySend(foundGroups.values.toList().sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BT", "Scan failed: $errorCode")
            }
        }
    }

    private suspend fun ProducerScope<List<DiscoveredGroup>>.runCleanupLoop(
        foundGroups: MutableMap<String, DiscoveredGroup>
    ) {
        while (true) {
            delay(2000)
            val now = System.currentTimeMillis()
            val timeout = 4000L

            val removed = foundGroups.values.removeIf { (now - it.lastSeen) > timeout }
            if (removed) {
                trySend(foundGroups.values.toList().sortedByDescending { it.rssi })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runScanPulseLoop(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        callback: ScanCallback
    ) {
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(APP_SERVICE_UUID), null)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        while (true) {
            try {
                scanner.startScan(listOf(filter), settings, callback)
                Log.d("BT", "Scan started (pulse)")
            } catch (e: Exception) {
                Log.e("BT", "Start scan failed", e)
            }

            delay(25000) // Scan for 25s

            try {
                scanner.stopScan(callback)
                Log.d("BT", "Scan paused (pulse)")
            } catch (e: Exception) {
                Log.e("BT", "Stop scan failed", e)
            }

            delay(500) // Rest for 0.5s
        }
    }
}
