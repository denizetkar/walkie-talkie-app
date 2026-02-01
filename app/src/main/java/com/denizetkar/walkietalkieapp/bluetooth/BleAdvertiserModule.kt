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
import com.denizetkar.walkietalkieapp.network.AdvertisingConfig
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleAdvertiserModule(
    private val adapter: BluetoothAdapter?,
    private val serverHandler: GattServerHandler
) {
    private var currentAdvertisingSet: AdvertisingSet? = null
    private var advertisingSetCallback: AdvertisingSetCallback? = null

    @SuppressLint("MissingPermission")
    fun start(config: AdvertisingConfig): Boolean {
        if (adapter == null) {
            Log.e("BleAdvertiser", "Bluetooth Adapter is NULL")
            return false
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e("BleAdvertiser", "BLE Advertiser is NULL (Bluetooth might be off or not supported)")
            return false
        }

        // Ensure Server is ready to accept connections
        serverHandler.startServer()

        // 1. Service Data (Topology)
        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)
        val payload = ByteBuffer.allocate(Config.PACKET_SERVICE_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(config.ownNodeId.toInt())
        payload.putInt(config.networkId.toInt())
        payload.put(config.hopsToRoot.toByte())
        payload.put(if (config.isAvailable) 1.toByte() else 0.toByte())

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(pUuid, payload.array())
            .build()

        // 2. Scan Response (Group Name)
        val nameBytes = HandshakeLogic.truncateUtf8(config.groupName, Config.MAX_ADVERTISING_NAME_BYTES)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(Config.BLE_MANUFACTURER_ID, nameBytes)
            .build()

        // If already running, update data only (Success assumed if we got this far)
        if (currentAdvertisingSet != null) {
            try {
                currentAdvertisingSet?.setAdvertisingData(mainData)
                currentAdvertisingSet?.setScanResponseData(scanResponseData)
                return true
            } catch (e: Exception) {
                Log.e("BleAdvertiser", "Failed to update set", e)
                // If update fails, try full restart
                stop()
            }
        }

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
                    Log.i("BleAdvertiser", "Advertising started successfully.")
                    currentAdvertisingSet = advertisingSet
                } else {
                    Log.e("BleAdvertiser", "Advertising failed to start. Status: $status")
                    // Note: We can't return 'false' from here (async), but the initial start call below catches immediate errors.
                }
            }
        }

        return try {
            advertiser.startAdvertisingSet(parameters, mainData, scanResponseData, null, null, advertisingSetCallback)
            true
        } catch (e: Exception) {
            Log.e("BleAdvertiser", "Start failed with exception", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val cb = advertisingSetCallback ?: return
        try {
            advertiser.stopAdvertisingSet(cb)
        } catch (_: Exception) {}
        advertisingSetCallback = null
        currentAdvertisingSet = null
    }
}