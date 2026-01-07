package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.ParcelUuid
import android.util.Log
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.logic.ProtocolUtils
import com.denizetkar.walkietalkieapp.network.AdvertisingConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleAdvertiserModule(
    private val adapter: BluetoothAdapter?,
    private val serverHandler: GattServerHandler
) {
    private var currentAdvertisingSet: AdvertisingSet? = null
    private var advertisingSetCallback: AdvertisingSetCallback? = null

    @SuppressLint("MissingPermission")
    fun start(config: AdvertisingConfig) {
        Log.d("BleAdvertiser", "Request to START advertising: ${config.groupName} (NetID: ${config.networkId})")

        if (adapter == null) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e("BleAdvertiser", "Bluetooth LE Advertiser not available")
            return
        }

        // 1. MAIN PACKET: Service Data (Topology)
        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)
        val payload = ByteBuffer.allocate(Config.PACKET_SERVICE_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // BIT-CAST: UInt to Int for the ByteBuffer
        payload.putInt(config.ownNodeId.toInt())
        payload.putInt(config.networkId.toInt())

        payload.put(config.hopsToRoot.toByte())
        payload.put(if (config.isAvailable) 1.toByte() else 0.toByte())

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(pUuid, payload.array())
            .build()

        // 2. SCAN RESPONSE: Manufacturer Data (Group Name)
        val nameBytes = ProtocolUtils.truncateUtf8(config.groupName, Config.MAX_ADVERTISING_NAME_BYTES)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(Config.BLE_MANUFACTURER_ID, nameBytes)
            .build()

        // OPTIMIZATION: If already running, just update data
        if (currentAdvertisingSet != null) {
            Log.d("BleAdvertiser", "Updating existing Advertising Set (NetID: ${config.networkId})")
            try {
                currentAdvertisingSet?.setAdvertisingData(mainData)
                currentAdvertisingSet?.setScanResponseData(scanResponseData)
            } catch (e: Exception) {
                Log.e("BleAdvertiser", "Failed to update advertising data", e)
                stop()
            }
            return
        }

        // 3. Start New Session
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        advertisingSetCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.i("BleAdvertiser", "Advertising Set STARTED successfully. (NetID: ${config.networkId})")
                    currentAdvertisingSet = advertisingSet
                    serverHandler.startServer()
                } else {
                    Log.e("BleAdvertiser", "Failed to start advertising set: $status")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.i("BleAdvertiser", "Advertising Set STOPPED")
                currentAdvertisingSet = null
            }
        }

        try {
            Log.d("BleAdvertiser", "Calling startAdvertisingSet...")
            advertiser.startAdvertisingSet(
                parameters,
                mainData,
                scanResponseData,
                null,
                null,
                advertisingSetCallback
            )
        } catch (e: Exception) {
            Log.e("BleAdvertiser", "startAdvertisingSet failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        Log.d("BleAdvertiser", "Request to STOP advertising")
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val cb = advertisingSetCallback ?: return
        try {
            advertiser.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            Log.e("BleAdvertiser", "Error stopping advertising set", e)
        }

        advertisingSetCallback = null
        currentAdvertisingSet = null
    }
}