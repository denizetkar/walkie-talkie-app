package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import app.cash.turbine.test
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.MainApplication
import com.denizetkar.walkietalkieapp.network.ServerEvent
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import com.denizetkar.walkietalkieapp.protocol.Packet
import com.denizetkar.walkietalkieapp.protocol.Protocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], application = MainApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GattServerHandlerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)
    private val mockGattServer = mockk<BluetoothGattServer>(relaxed = true)

    private lateinit var serverHandler: GattServerHandler
    private lateinit var gattCallback: BluetoothGattServerCallback

    private val accessCode = "1234"
    private val expectedNodeId = 99u

    private val device = mockk<BluetoothDevice> {
        every { address } returns "AA:BB:CC:DD:EE:FF"
    }
    private val controlChar = mockk<BluetoothGattCharacteristic> {
        every { uuid } returns Config.CHAR_CONTROL_UUID
    }
    private val dataChar = mockk<BluetoothGattCharacteristic> {
        every { uuid } returns Config.CHAR_DATA_UUID
    }
    private val mockService = mockk<BluetoothGattService>()

    @Before
    fun setup() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { mockAdapter.isEnabled } returns true
        every { bluetoothManager.adapter } returns mockAdapter

        val callbackSlot = slot<BluetoothGattServerCallback>()
        every { bluetoothManager.openGattServer(any(), capture(callbackSlot)) } returns mockGattServer

        every { mockGattServer.notifyCharacteristicChanged(any(), any(), any(), any()) } returns BluetoothStatusCodes.SUCCESS
        every { bluetoothManager.getConnectionState(any(), any()) } returns BluetoothProfile.STATE_DISCONNECTED
        every { mockGattServer.getService(Config.APP_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(Config.CHAR_CONTROL_UUID) } returns controlChar
        every { mockService.getCharacteristic(Config.CHAR_DATA_UUID) } returns dataChar

        // Inject the test dispatcher here
        serverHandler = GattServerHandler(context, testScope.backgroundScope, testDispatcher) { accessCode }
        serverHandler.startServer()
        gattCallback = callbackSlot.captured
    }

    @After
    fun tearDown() {
        serverHandler.stopServer()
    }

    @Test
    fun `Handshake Flow - Successful Authentication`() = testScope.runTest {
        serverHandler.serverEvents.test {
            // 1. Client Connects
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            val connectEvent = awaitItem()
            assertTrue(connectEvent is ServerEvent.ClientConnected)

            // 2. Client sends HELLO (0x01)
            val helloPacket = Packet.Control.Raw(Protocol.OP_HELLO, byteArrayOf()).toBytes()
            val notifyDataSlot = slot<ByteArray>()
            every { mockGattServer.notifyCharacteristicChanged(device, controlChar, true, capture(notifyDataSlot)) } returns BluetoothStatusCodes.SUCCESS

            gattCallback.onCharacteristicWriteRequest(device, 1, controlChar, false, true, 0, helloPacket)
            runCurrent() // Yield to OperationQueue

            verify { mockGattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 0, null) }
            gattCallback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            val challengeBytes = notifyDataSlot.captured
            val challengePacket = Packet.fromBytes(challengeBytes, true) as Packet.Control.Raw
            assertEquals(Protocol.OP_AUTH_CHALLENGE, challengePacket.opCode)
            val nonce = String(challengePacket.data, Charsets.UTF_8)

            // 3. Client Solves Challenge and sends RESPONSE (0x03)
            val responsePayload = HandshakeLogic.generateResponse(accessCode, nonce, expectedNodeId)
            val responsePacket = Packet.Control.Raw(Protocol.OP_AUTH_RESPONSE, responsePayload).toBytes()

            gattCallback.onCharacteristicWriteRequest(device, 2, controlChar, false, true, 0, responsePacket)
            runCurrent()
            gattCallback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)

            // 4. Verify Server emits Authenticated event and sends SUCCESS result (0x04)
            val authEvent = awaitItem() as ServerEvent.ClientAuthenticated
            assertEquals(expectedNodeId, authEvent.nodeId)

            val resultBytes = notifyDataSlot.captured
            val resultPacket = Packet.fromBytes(resultBytes, true) as Packet.Control.Raw
            assertEquals(Protocol.OP_AUTH_RESULT, resultPacket.opCode)
            assertEquals(0x01.toByte(), resultPacket.data[0])

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Message Sending - Sends Indication for Control and Notification for Audio`() = testScope.runTest {
        // Connect the device first to create its internal operation queue
        gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()

        val payload = byteArrayOf(0x99.toByte())

        // 1. Send Audio (Notification -> confirm = false)
        serverHandler.sendTo(device, payload, TransportDataType.AUDIO)
        runCurrent()
        verify(exactly = 1) {
            mockGattServer.notifyCharacteristicChanged(device, dataChar, false, payload)
        }

        // 2. Send Control (Indication -> confirm = true)
        serverHandler.sendTo(device, payload, TransportDataType.CONTROL)
        runCurrent()
        verify(exactly = 1) {
            mockGattServer.notifyCharacteristicChanged(device, controlChar, true, payload)
        }
    }

    @Test
    fun `Audio Reception - Emits MessageReceived for CHAR_DATA_UUID`() = testScope.runTest {
        serverHandler.serverEvents.test {
            val audioPayload = byteArrayOf(0x01, 0x02, 0x03)

            // Trigger WriteRequest directly to the Data characteristic
            gattCallback.onCharacteristicWriteRequest(device, 99, dataChar, false, false, 0, audioPayload)

            val event = awaitItem() as ServerEvent.MessageReceived
            assertEquals(TransportDataType.AUDIO, event.type)
            assertEquals(audioPayload.toList(), event.data.toList())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Ghost Disconnects - Ignored if manager reports connected`() = testScope.runTest {
        serverHandler.serverEvents.test {
            // First connect properly
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ServerEvent.ClientConnected)

            // Simulate the Android race condition: The callback says DISCONNECTED, but the Manager says CONNECTED
            every { bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) } returns BluetoothProfile.STATE_CONNECTED

            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

            // No ClientDisconnected event should be emitted
            expectNoEvents()
        }
    }

    @Test
    fun `Security - Duplicate Auth Response ignored safely`() = testScope.runTest {
        serverHandler.serverEvents.test {
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ServerEvent.ClientConnected)

            // Send an Auth Response WITHOUT a preceding Hello/Challenge (nonce is missing)
            val fakePayload = HandshakeLogic.generateResponse(accessCode, "fakeNonce", expectedNodeId)
            val responsePacket = Packet.Control.Raw(Protocol.OP_AUTH_RESPONSE, fakePayload).toBytes()

            gattCallback.onCharacteristicWriteRequest(device, 2, controlChar, false, true, 0, responsePacket)
            runCurrent()

            // It should be safely ignored and log a warning (no crash, no authenticated event, no result packet sent)
            verify(exactly = 0) { mockGattServer.notifyCharacteristicChanged(any(), any(), any(), any()) }
            expectNoEvents()
        }
    }

    @Test
    fun `MTU Failures - Cancels connection if MTU too low`() = testScope.runTest {
        serverHandler.serverEvents.test {
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ServerEvent.ClientConnected)

            every { bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) } returns BluetoothProfile.STATE_CONNECTED
            // Trigger MTU below minimum 300
            gattCallback.onMtuChanged(device, 23)
            runCurrent() // Yield so the fail() coroutine can emit the Error and call disconnect()

            val errorEvent = awaitItem() as ServerEvent.Error
            assertTrue(errorEvent.reason.message.contains("MTU too low"))

            // Verify that the fail() logic triggered a disconnect
            verify(exactly = 1) { mockGattServer.cancelConnection(device) }

            every { bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) } returns BluetoothProfile.STATE_DISCONNECTED
            // Manually simulate the Android Bluetooth stack responding to the cancellation request
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()

            // Drain the resulting disconnect event
            assertTrue(awaitItem() is ServerEvent.ClientDisconnected)
        }
    }

    @Test
    fun `Lifecycle Cleanup - Clears maps and queues on stopServer`() {
        // Just calling startServer (in setup) and stopServer (in teardown) handles the lifecycle tests.
        // We'll verify that close() was called on the underlying hardware object.
        serverHandler.stopServer()
        verify(exactly = 1) { mockGattServer.close() }

        // Ensure subsequent calls are safe
        serverHandler.stopServer()
    }

    // --- ADDITIONAL COVERAGE TESTS ---

    @Test
    fun `startServer - Handles null GattServer gracefully`() {
        // Simulate a scenario where Bluetooth is turned off, so openGattServer returns null
        val badBluetoothManager = mockk<BluetoothManager>()
        every { badBluetoothManager.openGattServer(any(), any()) } returns null

        val badContext = mockk<Context> {
            every { getSystemService(Context.BLUETOOTH_SERVICE) } returns badBluetoothManager
        }

        val badServerHandler = GattServerHandler(badContext, testScope.backgroundScope, testDispatcher) { "1234" }

        // Should log an error and return without crashing
        badServerHandler.startServer()
    }

    @Test
    fun `Requests - Replies to Descriptor and Unknown Characteristic Write Requests`() {
        // Core Bluetooth requirement: If a device writes to us with responseNeeded = true,
        // we MUST send a response, even if we don't care about the descriptor.

        // 1. Descriptor Write
        gattCallback.onDescriptorWriteRequest(device, 1, mockk(relaxed = true), false, true, 0, byteArrayOf())
        verify(exactly = 1) { mockGattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 0, null) }

        // 2. Characteristic Write (unrecognized characteristic)
        val unknownChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
        gattCallback.onCharacteristicWriteRequest(device, 2, unknownChar, false, true, 0, byteArrayOf())
        verify(exactly = 1) { mockGattServer.sendResponse(device, 2, BluetoothGatt.GATT_SUCCESS, 0, null) }
    }

    @Test
    fun `sendTo - Handles missing queue gracefully`() {
        // Try sending to a device that hasn't connected (so no queue exists)
        val unknownDevice = mockk<BluetoothDevice> { every { address } returns "FF:EE:DD:CC:BB:AA" }
        serverHandler.sendTo(unknownDevice, byteArrayOf(0x01), TransportDataType.CONTROL)

        // Verify we didn't crash and didn't attempt to notify the non-existent device
        verify(exactly = 0) { mockGattServer.notifyCharacteristicChanged(unknownDevice, any(), any(), any()) }
    }

    @Test
    fun `sendTo - Fails synchronously (Stack Busy) exhausts retries and emits Error`() = testScope.runTest {
        serverHandler.serverEvents.test {
            // Connect to establish the internal operation queue
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ServerEvent.ClientConnected)

            // Tell the mock the device is now fully connected
            every { bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) } returns BluetoothProfile.STATE_CONNECTED
            // Mock the stack busy response (returns ERROR_UNKNOWN instead of SUCCESS)
            every { mockGattServer.notifyCharacteristicChanged(device, controlChar, true, any()) } returns BluetoothStatusCodes.ERROR_UNKNOWN

            // Send Control Packet
            serverHandler.sendTo(device, byteArrayOf(0x01), TransportDataType.CONTROL)

            // Advance time past the internal retry backoff
            advanceTimeBy(Config.GATT_RETRY_COOLDOWN * Config.GATT_RETRY_ATTEMPTS + 1000L)
            runCurrent()

            val errorEvent = awaitItem() as ServerEvent.Error
            assertTrue(errorEvent.reason.message.contains("Notify Failed"))

            // Verify the fail logic also automatically triggered a disconnect
            verify(exactly = 1) { mockGattServer.cancelConnection(device) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendTo - Asynchronous Timeout exhausts retries and emits Error`() = testScope.runTest {
        serverHandler.serverEvents.test {
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            awaitItem()
            every { bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) } returns BluetoothProfile.STATE_CONNECTED
            // Mock Android ACCEPTING the packet, but we will purposefully NEVER fire onNotificationSent
            every { mockGattServer.notifyCharacteristicChanged(device, controlChar, true, any()) } returns BluetoothStatusCodes.SUCCESS

            // Send Control Packet
            serverHandler.sendTo(device, byteArrayOf(0x01), TransportDataType.CONTROL)

            // Advance time past the 3 attempts:
            val totalWait = (Config.BLE_OPERATION_TIMEOUT + Config.GATT_RETRY_COOLDOWN) * Config.GATT_RETRY_ATTEMPTS + 1000L
            advanceTimeBy(totalWait)
            runCurrent()

            val errorEvent = awaitItem() as ServerEvent.Error
            assertTrue(errorEvent.reason.message.contains("Notify Failed"))

            // Verify it actually tried 3 times before failing
            verify(exactly = Config.GATT_RETRY_ATTEMPTS) { mockGattServer.notifyCharacteristicChanged(any(), any(), any(), any()) }
            // Verify it tore down the dead connection
            verify(exactly = 1) { mockGattServer.cancelConnection(device) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Disconnect - Skips cancelConnection if already physically disconnected`() = testScope.runTest {
        // In our setup(), getConnectionState returns STATE_DISCONNECTED by default.

        // Call the suspending disconnect method
        serverHandler.disconnect(device)
        runCurrent()

        // Assert that we bypassed the Android stack call entirely
        verify(exactly = 0) { mockGattServer.cancelConnection(device) }
    }

    @Test
    fun `Disconnect - Handles system callback Timeout gracefully`() = testScope.runTest {
        // Connect device to set up the session
        gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()

        // Launch the suspending disconnect method
        val job = testScope.backgroundScope.launch {
            serverHandler.disconnect(device)
        }

        // Advance time past the Config.PEER_DISCONNECT_TIMEOUT.
        // We purposefully do NOT fire `gattCallback.onConnectionStateChange(STATE_DISCONNECTED)`,
        // simulating the Android stack hanging/freezing.
        advanceTimeBy(Config.PEER_DISCONNECT_TIMEOUT + 500L)
        runCurrent()

        // Disconnect should complete (time out) without crashing the app
        assertTrue(job.isCompleted)

        // Verify that cleanupDeviceData STILL happened despite the timeout by trying to send to it
        serverHandler.sendTo(device, byteArrayOf(0x01), TransportDataType.CONTROL)
        verify(exactly = 0) { mockGattServer.notifyCharacteristicChanged(any(), any(), any(), any()) }
    }
}