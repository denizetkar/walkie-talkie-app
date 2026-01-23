package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.TransportNode
import com.denizetkar.walkietalkieapp.utils.ScanRateLimiter
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
    private val _events = MutableSharedFlow<TransportNode>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TransportNode> = _events.asSharedFlow()

    private val activeSession = AtomicReference<ScanSession?>(null)
    private val rateLimiter = ScanRateLimiter()

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null) return
        if (activeSession.get() != null) return

        val token = rateLimiter.tryAcquire() ?: return
        val newSession = ScanSession()

        if (activeSession.compareAndSet(null, newSession)) {
            if (!newSession.start()) {
                activeSession.set(null)
                rateLimiter.rollback(token)
            }
        } else {
            rateLimiter.rollback(token)
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
                activeSession.compareAndSet(this@ScanSession, null)
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
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            return try {
                scanner.startScan(filters, settings, callback)
                true
            } catch (_: Exception) { false }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            try {
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
        val hops = buffer.get().toInt()
        val isAvailable = (buffer.get().toInt() == 1)

        val nameBytes = record.getManufacturerSpecificData(Config.BLE_MANUFACTURER_ID)
        val groupName = if (nameBytes != null) String(nameBytes, Charsets.UTF_8) else "Unknown"

        val node = TransportNode(
            id = result.device.address,
            name = groupName,
            rssi = result.rssi,
            nodeId = nodeId,
            networkId = networkId,
            hopsToRoot = hops,
            isAvailable = isAvailable
        )
        _events.tryEmit(node)
    }
}