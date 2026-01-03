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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleAdvertiserModule(
    private val adapter: BluetoothAdapter,
    private val serverHandler: GattServerHandler
) {
    private var currentAdvertisingSet: AdvertisingSet? = null
    private var advertisingSetCallback: AdvertisingSetCallback? = null

    @SuppressLint("MissingPermission")
    fun start(groupName: String, myNodeId: Int, networkId: Int, hops: Int, isAvailable: Boolean) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)
        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(myNodeId)
        payload.putInt(networkId)
        payload.put(hops.toByte())
        payload.put(if (isAvailable) 1.toByte() else 0.toByte())

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(pUuid, payload.array())
            .build()

        val nameBytes = ProtocolUtils.truncateUtf8(groupName, Config.MAX_ADVERTISING_NAME_BYTES)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, nameBytes)
            .build()

        if (currentAdvertisingSet != null) {
            Log.d("BleAdvertiser", "Updating existing Advertising Set (NetID: $networkId)")
            currentAdvertisingSet?.setAdvertisingData(mainData)
            currentAdvertisingSet?.setScanResponseData(scanResponseData)
            return
        }

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        advertisingSetCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.d("BleAdvertiser", "Advertising Set Started: $groupName (NetID: $networkId)")
                    currentAdvertisingSet = advertisingSet
                    serverHandler.startServer()
                } else {
                    Log.e("BleAdvertiser", "Failed to start advertising set: $status")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.d("BleAdvertiser", "Advertising Set Stopped")
                currentAdvertisingSet = null
            }
        }

        advertiser.startAdvertisingSet(
            parameters,
            mainData,
            scanResponseData,
            null,
            null,
            advertisingSetCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
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