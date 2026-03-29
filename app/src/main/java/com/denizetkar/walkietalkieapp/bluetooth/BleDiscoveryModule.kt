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
import com.denizetkar.walkietalkieapp.network.DiscoveryEvent
import com.denizetkar.walkietalkieapp.network.TransportNode
import com.denizetkar.walkietalkieapp.utils.ScanRateLimiter
import com.denizetkar.walkietalkieapp.utils.retryWithBackoffNullable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class BleDiscoveryModule(
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<DiscoveryEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DiscoveryEvent> = _events.asSharedFlow()

    private val activeSession = AtomicReference<ScanSession?>(null)
    private val rateLimiter = ScanRateLimiter()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (adapter == null) {
            Log.e("BleDiscovery", "Bluetooth Adapter is NULL")
            return false
        }
        // If scanner is null, Bluetooth is likely off
        if (adapter.bluetoothLeScanner == null) {
            Log.e("BleDiscovery", "BLE Advertiser is NULL (Bluetooth might be off or not supported)")
            return false
        }
        // Idempotency: If already scanning, return true
        if (activeSession.get() != null) return true

        val token = retryWithBackoffNullable(
            times = Config.SCAN_RETRY_ATTEMPTS, initialDelay = Config.SCAN_RETRY_COOLDOWN,
        ){ rateLimiter.tryAcquire() } ?: run {
            Log.e("BleDiscovery", "Scan Rate Limited (Android Throttling)")
            return false
        }
        val newSession = ScanSession()
        return if (activeSession.compareAndSet(null, newSession)) {
            if (!newSession.start()) {
                Log.e("BleDiscovery", "Failed to start Scan Session")
                activeSession.set(null)
                rateLimiter.rollback(token)
                false
            } else {
                true
            }
        } else {
            // Race condition lost, rollback token
            rateLimiter.rollback(token)
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        activeSession.getAndSet(null)?.stop()
    }

    private inner class ScanSession {
        private val scanner = adapter?.bluetoothLeScanner
        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { scope.launch { processScanResult(it) } }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result -> scope.launch { processScanResult(result) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BleDiscovery", "Scan Failed inside callback. Error: $errorCode")
                activeSession.compareAndSet(this@ScanSession, null)
                _events.tryEmit(DiscoveryEvent.ScanFailed(errorCode))
            }
        }

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (scanner == null) return false
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(Config.APP_SERVICE_UUID), null)
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            return try {
                Log.d("BleDiscovery", "Starting BLE discovery...")
                scanner.startScan(filters, settings, callback)
                true
            } catch (e: Exception) {
                Log.e("BleDiscovery", "Start Scan Exception", e)
                false
            }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            try {
                Log.d("BleDiscovery", "Stopping BLE discovery...")
                scanner?.stopScan(callback)
            } catch (_: Exception) {}
        }
    }

    private fun processScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.serviceData?.get(ParcelUuid(Config.APP_SERVICE_UUID)) ?: return
        if (serviceData.size < Config.PACKET_SERVICE_DATA_SIZE) return

        val buffer = ByteBuffer.wrap(serviceData).order(ByteOrder.LITTLE_ENDIAN)
        val nodeId = buffer.int.toUInt()
        val networkId = buffer.int.toUInt()
        val hops = buffer.get().toInt() and 0xFF
        val isAvailable = (buffer.get().toInt() == 1)

        val nameBytes = record.getManufacturerSpecificData(Config.BLE_MANUFACTURER_ID)
        val groupName = if (nameBytes != null) String(nameBytes, Charsets.UTF_8) else ""

        val node = TransportNode(
            id = result.device.address,
            name = groupName,
            rssi = result.rssi,
            nodeId = nodeId,
            networkId = networkId,
            hopsToRoot = hops,
            isAvailable = isAvailable
        )
        _events.tryEmit(DiscoveryEvent.NodeFound(node))
    }
}