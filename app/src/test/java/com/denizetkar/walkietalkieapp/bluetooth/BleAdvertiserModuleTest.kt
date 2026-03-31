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

        val config = AdvertisingConfig(100u, "Test", 1u, 1u, 0, true)
        val result = advertiserModule.start(config)

        assertFalse("Should return false when advertiser is null", result)
    }

    @Test
    fun `Serialization - Encodes Topology and Manufacturer Data`() {
        val config = AdvertisingConfig(
            groupId = 0x1A2B3C4Du,
            groupName = "Alpha",
            ownNodeId = 0x11223344u,
            rootNodeId = 0x55667788u,
            hopsToRoot = 2,
            isAvailable = true
        )

        // 1. Create TWO slots to capture both data packets
        val mainDataSlot = slot<AdvertiseData>()
        val scanResponseSlot = slot<AdvertiseData>()
        val cbSlot = slot<AdvertisingSetCallback>()

        // 2. Capture the 2nd argument (mainData) and 3rd argument (scanResponse)
        every {
            mockAdvertiser.startAdvertisingSet(
                any(),
                capture(mainDataSlot),
                capture(scanResponseSlot),
                any(),
                any(),
                capture(cbSlot)
            )
        } just runs

        val result = advertiserModule.start(config)
        assertTrue("Should successfully request advertising start", result)

        verify { mockServerHandler.startServer() }

        // Assert 1: Service Data Payload (Topology) is in mainDataSlot
        val serviceData = mainDataSlot.captured.serviceData[ParcelUuid(Config.APP_SERVICE_UUID)]
        requireNotNull(serviceData) { "Service data must be present" }
        assertEquals("Payload must be exactly 10 bytes", 10, serviceData.size)

        val buffer = ByteBuffer.wrap(serviceData).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x11223344, buffer.int)
        assertEquals(0x55667788, buffer.int)
        assertEquals(2.toByte(), buffer.get())
        assertEquals(1.toByte(), buffer.get())

        // Assert 2: Manufacturer Data (Group ID + Name) is in scanResponseSlot
        val manufacturerData = scanResponseSlot.captured.manufacturerSpecificData[Config.BLE_MANUFACTURER_ID]
        requireNotNull(manufacturerData) { "Manufacturer data must be present" }

        // Assert payload structure: [4 bytes Group ID] +[5 bytes "Alpha"]
        assertEquals("Payload must be 9 bytes", 9, manufacturerData.size)
        val manufacturerBuffer = ByteBuffer.wrap(manufacturerData).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x1A2B3C4D, manufacturerBuffer.int) // Group ID

        val nameBytes = ByteArray(5)
        manufacturerBuffer.get(nameBytes)
        assertEquals("Alpha", String(nameBytes, Charsets.UTF_8))
    }

    @Test
    fun `Idempotency - Updates existing AdvertisingSet instead of creating a new one`() {
        val config1 = AdvertisingConfig(100u, "Alpha", 1u, 1u, 0, true)
        val cbSlot = slot<AdvertisingSetCallback>()

        every {
            mockAdvertiser.startAdvertisingSet(any(), any(), any(), any(), any(), capture(cbSlot))
        } just runs

        // 1. Initial Start
        advertiserModule.start(config1)

        // Simulate Android framework async success callback
        cbSlot.captured.onAdvertisingSetStarted(mockAdvertisingSet, 0, AdvertisingSetCallback.ADVERTISE_SUCCESS)

        // 2. Second Start (Configuration Update)
        val config2 = AdvertisingConfig(100u, "Alpha", 1u, 2u, 1, false)
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