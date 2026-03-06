package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.AdvertisingConfig
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class BleAdvertiserModuleTest {

    private val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)
    private val mockAdvertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
    private val mockServerHandler = mockk<GattServerHandler>(relaxed = true)
    private val mockAdvertisingSet = mockk<AdvertisingSet>(relaxed = true)

    private lateinit var advertiserModule: BleAdvertiserModule

    @Before
    fun setup() {
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser
        advertiserModule = BleAdvertiserModule(mockAdapter, mockServerHandler)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Hardware Unavailable - Returns false gracefully`() {
        // Setup: Bluetooth is off or not supported
        every { mockAdapter.bluetoothLeAdvertiser } returns null

        val config = AdvertisingConfig("Test", 1u, 1u, 0, true)
        val result = advertiserModule.start(config)

        assertFalse("Should return false when advertiser is null", result)
    }

    @Test
    fun `Serialization - Encodes Topology to 10-byte Little-Endian Payload`() {
        val config = AdvertisingConfig(
            groupName = "Alpha",
            ownNodeId = 0x11223344u,
            networkId = 0x55667788u,
            hopsToRoot = 2,
            isAvailable = true
        )

        val dataSlot = slot<AdvertiseData>()
        val cbSlot = slot<AdvertisingSetCallback>()

        every {
            mockAdvertiser.startAdvertisingSet(any(), capture(dataSlot), any(), any(), any(), capture(cbSlot))
        } just runs

        val result = advertiserModule.start(config)
        assertTrue("Should successfully request advertising start", result)

        // Ensure the server was told to be ready
        verify { mockServerHandler.startServer() }

        // Assert 1: Service Data Payload
        val serviceData = dataSlot.captured.serviceData[ParcelUuid(Config.APP_SERVICE_UUID)]
        requireNotNull(serviceData) { "Service data must be present" }
        assertEquals("Payload must be exactly 10 bytes", 10, serviceData.size)

        // Verify Little-Endian Serialization
        val buffer = ByteBuffer.wrap(serviceData).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x11223344, buffer.int)
        assertEquals(0x55667788, buffer.int)
        assertEquals(2.toByte(), buffer.get())
        assertEquals(1.toByte(), buffer.get()) // isAvailable = true
    }

    @Test
    fun `Idempotency - Updates existing AdvertisingSet instead of creating a new one`() {
        val config1 = AdvertisingConfig("Alpha", 1u, 1u, 0, true)
        val cbSlot = slot<AdvertisingSetCallback>()

        every {
            mockAdvertiser.startAdvertisingSet(any(), any(), any(), any(), any(), capture(cbSlot))
        } just runs

        // 1. Initial Start
        advertiserModule.start(config1)

        // Simulate Android framework async success callback
        cbSlot.captured.onAdvertisingSetStarted(mockAdvertisingSet, 0, AdvertisingSetCallback.ADVERTISE_SUCCESS)

        // 2. Second Start (Configuration Update)
        val config2 = AdvertisingConfig("Alpha", 1u, 2u, 1, false)
        val updateDataSlot = slot<AdvertiseData>()

        every { mockAdvertisingSet.setAdvertisingData(capture(updateDataSlot)) } just runs

        val updateResult = advertiserModule.start(config2)
        assertTrue("Update should succeed", updateResult)

        // 3. Assertions
        // Verify startAdvertisingSet was NOT called a second time
        verify(exactly = 1) { mockAdvertiser.startAdvertisingSet(any(), any(), any(), any(), any(), any()) }

        // Verify setAdvertisingData WAS called with the new configuration
        verify(exactly = 1) { mockAdvertisingSet.setAdvertisingData(any()) }

        val newPayload = updateDataSlot.captured.serviceData[ParcelUuid(Config.APP_SERVICE_UUID)]!!
        val buffer = ByteBuffer.wrap(newPayload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8) // Skip IDs
        assertEquals("Hops should be updated to 1", 1.toByte(), buffer.get())
        assertEquals("Available should be updated to 0 (false)", 0.toByte(), buffer.get())
    }
}