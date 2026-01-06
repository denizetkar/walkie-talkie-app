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
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TransportNode> = _events.asSharedFlow()

    private val activeSession = AtomicReference<ScanSession?>(null)
    private val rateLimiter = ScanRateLimiter()

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null) return

        if (activeSession.get() != null) {
            Log.d("BleDiscovery", "Ignored: Scan already in progress.")
            return
        }

        val token = rateLimiter.tryAcquire() ?: run {
            Log.w("BleDiscovery", "Ignored: Rate limit reached.")
            return
        }

        val newSession = ScanSession()
        if (activeSession.compareAndSet(null, newSession)) {
            // We won the race to set the session. Now talk to hardware.
            val success = newSession.start()
            if (!success) {
                // Hardware failed. Cleanup and Refund this specific token.
                activeSession.set(null)
                rateLimiter.rollback(token)
            }
        } else {
            // We lost the race (another thread started a session).
            // Refund our specific token.
            rateLimiter.rollback(token)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val session = activeSession.getAndSet(null)
        if (session != null) {
            session.stop()
            Log.d("BleDiscovery", "Discovery Stopped")
        }
    }

    private inner class ScanSession {
        private val scanner = adapter?.bluetoothLeScanner

        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                scope.launch { processScanResult(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                if (results == null) return
                scope.launch { results.forEach { processScanResult(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BleDiscovery", "Scan Failed with error: $errorCode")
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
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            return try {
                scanner.startScan(filters, settings, callback)
                Log.d("BleDiscovery", "Discovery Started")
                true
            } catch (e: Exception) {
                Log.e("BleDiscovery", "Start Scan Error", e)
                false
            }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            try {
                scanner?.stopScan(callback)
                Log.d("BleDiscovery", "Discovery Stopped")
            } catch (e: Exception) {
                Log.w("BleDiscovery", "Error stopping scan", e)
            }
        }
    }

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

        _events.tryEmit(node)
    }
}