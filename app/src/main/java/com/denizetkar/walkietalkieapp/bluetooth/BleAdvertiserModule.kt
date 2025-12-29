package com.denizetkar.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(groupName: String, myNodeId: Int, networkId: Int, hops: Int) {
        // 1. If already advertising, stop first (to update data)
        stop()

        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val pUuid = ParcelUuid(Config.APP_SERVICE_UUID)

        // Payload: [NodeID(4)] [NetworkID(4)] [Hops(1)] [Avail(1)]
        val payload = ByteBuffer.allocate(10)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(myNodeId)
        payload.putInt(networkId)
        payload.put(hops.toByte())
        payload.put(1.toByte()) // TODO: Pass availability from Manager if needed

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(pUuid, payload.array())
            .build()

        val nameBytes = ProtocolUtils.truncateUtf8(groupName, Config.MAX_ADVERTISING_NAME_BYTES)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, nameBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BleAdvertiser", "Advertising Started: $groupName (NetID: $networkId)")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BleAdvertiser", "Advertising Failed: $errorCode")
            }
        }

        try {
            advertiser.startAdvertising(settings, mainData, scanResponseData, advertiseCallback)
            serverHandler.startServer()
        } catch (e: Exception) {
            Log.e("BleAdvertiser", "Start Advertising Error", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiseCallback?.let {
            try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
        }
        advertiseCallback = null
    }
}