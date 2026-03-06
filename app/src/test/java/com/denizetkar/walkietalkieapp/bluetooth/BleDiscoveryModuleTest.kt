package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import app.cash.turbine.test
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.utils.retryWithBackoffNullable
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
@OptIn(ExperimentalCoroutinesApi::class)
class BleDiscoveryModuleTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)
    private val mockScanner = mockk<BluetoothLeScanner>(relaxed = true)

    private lateinit var discoveryModule: BleDiscoveryModule

    @Before
    fun setup() {
        every { mockAdapter.bluetoothLeScanner } returns mockScanner
        discoveryModule = BleDiscoveryModule(mockAdapter, testScope.backgroundScope)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Rate Limiter Integration - Blocks scan if Android quota exceeded`() {
        // We mock the top-level retry function instead of the ScanRateLimiter.
        // Why?
        // 1. discoveryModule instantiated the rate limiter in @Before, escaping mockkConstructor.
        // 2. If we let the real retry loop run and fail, Thread.sleep(1000) would make this test take 5 seconds!
        mockkStatic("com.denizetkar.walkietalkieapp.utils.RetryKt")

        // Simulate the retry wrapper exhausting its attempts and returning null
        every {
            retryWithBackoffNullable<Long>(any(), any(), any(), any())
        } returns null

        val result = discoveryModule.start()

        assertFalse("Should block scan start to prevent Android OS Exception", result)
        verify(exactly = 0) { mockScanner.startScan(any<List<android.bluetooth.le.ScanFilter>>(), any(), any<ScanCallback>()) }

        // Clean up the static mock so it doesn't affect other tests
        unmockkStatic("com.denizetkar.walkietalkieapp.utils.RetryKt")
    }

    @Test
    fun `Deserialization - Successfully parses 10-byte payload into TransportNode`() = testScope.runTest {
        val cbSlot = slot<ScanCallback>()
        every { mockScanner.startScan(any<List<android.bluetooth.le.ScanFilter>>(), any(), capture(cbSlot)) } just runs

        assertTrue("Should start scanning successfully", discoveryModule.start())

        // 1. Construct Mock Payload (Little Endian)
        val serviceDataBuffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        serviceDataBuffer.putInt(0x99887766.toInt()) // Node ID
        serviceDataBuffer.putInt(0x11223344) // Network ID
        serviceDataBuffer.put(3.toByte())            // Hops
        serviceDataBuffer.put(1.toByte())            // Available = true

        val manufacturerData = "TestGroup".toByteArray(Charsets.UTF_8)

        // 2. Mock Android ScanResult
        val mockDevice = mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val mockRecord = mockk<ScanRecord> {
            every { serviceData } returns mapOf(ParcelUuid(Config.APP_SERVICE_UUID) to serviceDataBuffer.array())
            every { getManufacturerSpecificData(Config.BLE_MANUFACTURER_ID) } returns manufacturerData
        }
        val mockResult = mockk<ScanResult> {
            every { device } returns mockDevice
            every { scanRecord } returns mockRecord
            every { rssi } returns -42
        }

        // 3. Setup Turbine to listen to the events flow
        discoveryModule.events.test {
            // Trigger the callback manually
            cbSlot.captured.onScanResult(1, mockResult)

            // Assert the emitted TransportNode
            val node = awaitItem()

            assertEquals("AA:BB:CC:DD:EE:FF", node.id)
            assertEquals("TestGroup", node.name)
            assertEquals(-42, node.rssi)
            assertEquals(0x99887766u, node.nodeId)
            assertEquals(0x11223344u, node.networkId)
            assertEquals(3, node.hopsToRoot)
            assertTrue(node.isAvailable)
        }
    }

    @Test
    fun `Resilience - Silently drops truncated or malformed payloads`() = testScope.runTest {
        val cbSlot = slot<ScanCallback>()
        every { mockScanner.startScan(any<List<android.bluetooth.le.ScanFilter>>(), any(), capture(cbSlot)) } just runs

        discoveryModule.start()

        // 1. Construct Malformed Payload (Only 4 bytes instead of 10)
        val badPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val mockDevice = mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val mockRecord = mockk<ScanRecord> {
            every { serviceData } returns mapOf(ParcelUuid(Config.APP_SERVICE_UUID) to badPayload)
            every { getManufacturerSpecificData(Config.BLE_MANUFACTURER_ID) } returns null
        }
        val mockResult = mockk<ScanResult> {
            every { device } returns mockDevice
            every { scanRecord } returns mockRecord
            every { rssi } returns -42
        }

        discoveryModule.events.test {
            // Trigger callback
            cbSlot.captured.onScanResult(1, mockResult)

            // Assert NO item is emitted (it should drop silently, not crash)
            expectNoEvents()
        }
    }
}