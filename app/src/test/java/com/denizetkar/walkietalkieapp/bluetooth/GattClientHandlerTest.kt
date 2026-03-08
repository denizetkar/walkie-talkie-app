package com.denizetkar.walkietalkieapp.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import app.cash.turbine.test
import com.denizetkar.walkietalkieapp.Config
import com.denizetkar.walkietalkieapp.network.ClientEvent
import com.denizetkar.walkietalkieapp.network.TransportDataType
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import com.denizetkar.walkietalkieapp.protocol.Packet
import com.denizetkar.walkietalkieapp.protocol.Protocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class GattClientHandlerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val context = mockk<Context>(relaxed = true)
    private val mockGatt = mockk<BluetoothGatt>(relaxed = true)

    private lateinit var clientHandler: GattClientHandler
    private lateinit var gattCallback: BluetoothGattCallback

    private val accessCode = "5678"
    private val myNodeId = 42u

    private val device = mockk<BluetoothDevice> {
        every { address } returns "11:22:33:44:55:66"
    }

    private val mockService = mockk<BluetoothGattService>()
    private val controlChar = mockk<BluetoothGattCharacteristic> {
        every { uuid } returns Config.CHAR_CONTROL_UUID
    }
    private val dataChar = mockk<BluetoothGattCharacteristic> {
        every { uuid } returns Config.CHAR_DATA_UUID
    }
    private val mockDescriptor = mockk<BluetoothGattDescriptor> {
        every { characteristic } returns controlChar
    }

    @Before
    fun setup() {
        val callbackSlot = slot<BluetoothGattCallback>()
        every { device.connectGatt(any(), any(), capture(callbackSlot), any()) } returns mockGatt

        every { mockGatt.writeCharacteristic(any(), any(), any()) } returns BluetoothStatusCodes.SUCCESS
        every { mockGatt.writeDescriptor(any(), any()) } returns BluetoothStatusCodes.SUCCESS

        // FIX: Relaxed mocks return false for booleans. Explicitly return true.
        every { mockGatt.discoverServices() } returns true
        every { mockGatt.setCharacteristicNotification(any(), any()) } returns true
        every { mockGatt.requestMtu(any()) } returns true

        every { mockGatt.getService(Config.APP_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(Config.CHAR_CONTROL_UUID) } returns controlChar
        every { mockService.getCharacteristic(Config.CHAR_DATA_UUID) } returns dataChar
        every { controlChar.getDescriptor(GattServerHandler.CCCD_UUID) } returns mockDescriptor
        every { dataChar.getDescriptor(GattServerHandler.CCCD_UUID) } returns mockDescriptor

        // Inject the test dispatcher
        clientHandler = GattClientHandler(context, testScope.backgroundScope, device, myNodeId, accessCode, testDispatcher)

        clientHandler.connect()
        gattCallback = callbackSlot.captured
    }

    @After
    fun tearDown() {
        clientHandler.close()
    }

    @Test
    fun `Handshake Flow - Successful Authentication & MTU Request`() = testScope.runTest {
        clientHandler.clientEvents.test {
            val writeDataSlot = slot<ByteArray>()
            every { mockGatt.writeCharacteristic(controlChar, capture(writeDataSlot), any()) } returns BluetoothStatusCodes.SUCCESS

            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ClientEvent.Connected)
            runCurrent() // Yield to OperationQueue for discovery

            verify { mockGatt.discoverServices() }

            gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
            advanceTimeBy(Config.GATT_SUBSCRIPTION_DELAY)
            runCurrent()

            gattCallback.onDescriptorWrite(mockGatt, mockDescriptor, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gattCallback.onDescriptorWrite(mockGatt, mockDescriptor, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            // 3. Client sent HELLO. Satisfy callback.
            gattCallback.onCharacteristicWrite(mockGatt, controlChar, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            val helloPacket = Packet.fromBytes(writeDataSlot.captured, true) as Packet.Control.Raw
            assertEquals(Protocol.OP_HELLO, helloPacket.opCode)

            // 4. Server replies with CHALLENGE
            val nonce = "xyz123"
            val challengePacket = Packet.Control.Raw(Protocol.OP_AUTH_CHALLENGE, nonce.toByteArray(Charsets.UTF_8)).toBytes()
            gattCallback.onCharacteristicChanged(mockGatt, controlChar, challengePacket)
            runCurrent()

            gattCallback.onCharacteristicWrite(mockGatt, controlChar, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            val responsePacket = Packet.fromBytes(writeDataSlot.captured, true) as Packet.Control.Raw
            assertEquals(Protocol.OP_AUTH_RESPONSE, responsePacket.opCode)
            assertEquals(myNodeId, HandshakeLogic.verifyResponse(responsePacket.data, accessCode, nonce))

            // 5. Server replies with AUTH_RESULT SUCCESS
            val resultPacket = Packet.Control.Raw(Protocol.OP_AUTH_RESULT, byteArrayOf(0x01)).toBytes()
            gattCallback.onCharacteristicChanged(mockGatt, controlChar, resultPacket)
            runCurrent()

            verify { mockGatt.requestMtu(Config.BLE_MTU_TARGET) }

            // 7. Satisfy MTU
            gattCallback.onMtuChanged(mockGatt, 512, BluetoothGatt.GATT_SUCCESS)

            assertTrue(awaitItem() is ClientEvent.Authenticated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Handshake Flow - Invalid Access Code NACK`() = testScope.runTest {
        clientHandler.clientEvents.test {
            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ClientEvent.Connected)

            val failPacket = Packet.Control.Raw(Protocol.OP_AUTH_RESULT, byteArrayOf(0x00)).toBytes()
            gattCallback.onCharacteristicChanged(mockGatt, controlChar, failPacket)

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("Access code rejected"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Connection Drops - Emits Disconnected on STATE_DISCONNECTED`() = testScope.runTest {
        clientHandler.clientEvents.test {
            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

            val event = awaitItem()
            assertTrue("Should emit Disconnected event", event is ClientEvent.Disconnected)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Connection Drops - Emits Error on GATT failure`() = testScope.runTest {
        clientHandler.clientEvents.test {
            // Android sometimes sends GATT_ERROR (133)
            gattCallback.onConnectionStateChange(mockGatt, 133, BluetoothProfile.STATE_CONNECTED)

            val event = awaitItem() as ClientEvent.Error
            assertTrue("Should emit Error on GATT failure", event.reason.message.contains("Status 133"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `MTU Failures - Emits Error if MTU too low`() = testScope.runTest {
        clientHandler.clientEvents.test {
            // Simulate MTU negotiation concluding with an unacceptably low value
            gattCallback.onMtuChanged(mockGatt, 23, BluetoothGatt.GATT_SUCCESS)

            val event = awaitItem() as ClientEvent.Error
            assertTrue("Should emit Error if MTU is below minimum", event.reason.message.contains("MTU Negotiation Failed"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Message Sending - Respects MTU and enqueues packets correctly`() = testScope.runTest {
        // Assume default MTU (23). Max payload is 20.
        val tooLargePacket = ByteArray(25)
        val validPacket = ByteArray(15)

        clientHandler.sendMessage(TransportDataType.AUDIO, tooLargePacket)
        runCurrent()

        // Ensure no write occurred for the oversized packet
        verify(exactly = 0) { mockGatt.writeCharacteristic(dataChar, any(), any()) }

        // Send a valid packet
        clientHandler.sendMessage(TransportDataType.AUDIO, validPacket)
        runCurrent()

        verify(exactly = 1) {
            mockGatt.writeCharacteristic(dataChar, validPacket, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }

        // Fulfill the suspending callback so the queue can process the next one
        gattCallback.onCharacteristicWrite(mockGatt, dataChar, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        // Send a control packet
        clientHandler.sendMessage(TransportDataType.CONTROL, validPacket)
        runCurrent()

        verify(exactly = 1) {
            mockGatt.writeCharacteristic(controlChar, validPacket, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    @Test
    fun `Audio Reception - Emits MessageReceived for CHAR_DATA_UUID`() = testScope.runTest {
        clientHandler.clientEvents.test {
            val audioPayload = byteArrayOf(0x01, 0x02, 0x03)

            // Trigger characteristic changed for the AUDIO char
            gattCallback.onCharacteristicChanged(mockGatt, dataChar, audioPayload)

            val event = awaitItem() as ClientEvent.MessageReceived
            assertEquals(TransportDataType.AUDIO, event.type)
            assertEquals(audioPayload.toList(), event.data.toList())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Lifecycle Cleanup - close cancels scope and disconnects`() {
        // Verify state prior to close
        assertTrue("Scope should be active", clientHandler.scope.isActive)

        clientHandler.disconnect()
        verify(exactly = 1) { mockGatt.disconnect() }

        clientHandler.close()
        verify(exactly = 1) { mockGatt.close() }
        assertFalse("Scope should be cancelled on close", clientHandler.scope.isActive)
    }

    @Test
    fun `Handshake Flow - Target missing WalkieTalkie Service emits Error`() = testScope.runTest {
        clientHandler.clientEvents.test {
            // Override the setup to simulate a device that doesn't have our service
            every { mockGatt.getService(Config.APP_SERVICE_UUID) } returns null

            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ClientEvent.Connected)
            runCurrent()

            // Trigger discovery callback. The OperationQueue will try to proceed but will fail to find the service.
            gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("Target does not have the WalkieTalkie Service"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Handshake Flow - Service Discovery Failed emits Error`() = testScope.runTest {
        clientHandler.clientEvents.test {
            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ClientEvent.Connected)
            runCurrent()

            // Simulate Android returning a GATT_FAILURE during discovery
            gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_FAILURE)
            runCurrent()

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("Service Discovery Failed"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Handshake Flow - Descriptor Write Failed emits Error`() = testScope.runTest {
        clientHandler.clientEvents.test {
            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            awaitItem()
            runCurrent()

            gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
            advanceTimeBy(Config.GATT_SUBSCRIPTION_DELAY)
            runCurrent()

            // Simulate the first descriptor write failing asynchronously
            gattCallback.onDescriptorWrite(mockGatt, mockDescriptor, BluetoothGatt.GATT_FAILURE)
            runCurrent()

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("Descriptor Write Failed"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Handshake Flow - MTU Request Rejected emits Error`() = testScope.runTest {
        clientHandler.clientEvents.test {
            // Mock the MTU request to fail immediately (returns false)
            every { mockGatt.requestMtu(any()) } returns false

            // Connect & discover
            gattCallback.onConnectionStateChange(mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            awaitItem()
            runCurrent()
            gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
            advanceTimeBy(Config.GATT_SUBSCRIPTION_DELAY)
            runCurrent()

            // Subscriptions
            gattCallback.onDescriptorWrite(mockGatt, mockDescriptor, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gattCallback.onDescriptorWrite(mockGatt, mockDescriptor, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            // Hello sent
            gattCallback.onCharacteristicWrite(mockGatt, controlChar, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            // Server Challenge
            val challengePacket = Packet.Control.Raw(Protocol.OP_AUTH_CHALLENGE, "xyz123".toByteArray(Charsets.UTF_8)).toBytes()
            gattCallback.onCharacteristicChanged(mockGatt, controlChar, challengePacket)
            runCurrent()

            // Response sent
            gattCallback.onCharacteristicWrite(mockGatt, controlChar, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            // Server Success (Triggers MTU request which we mocked to fail)
            val resultPacket = Packet.Control.Raw(Protocol.OP_AUTH_RESULT, byteArrayOf(0x01)).toBytes()
            gattCallback.onCharacteristicChanged(mockGatt, controlChar, resultPacket)
            runCurrent()

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("MTU Request Rejected"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Message Sending - Fails synchronously (Stack Busy) exhausts retries and emits Error`() = testScope.runTest {
        clientHandler.clientEvents.test {
            // Mock the write to fail immediately (simulate Bluetooth stack is busy/crashing)
            every { mockGatt.writeCharacteristic(controlChar, any(), any()) } returns BluetoothStatusCodes.ERROR_UNKNOWN

            // Try to send a control message
            clientHandler.sendMessage(TransportDataType.CONTROL, byteArrayOf(0x01))

            // The operation queue will retry Config.GATT_RETRY_ATTEMPTS times
            advanceTimeBy(Config.GATT_RETRY_COOLDOWN * Config.GATT_RETRY_ATTEMPTS + 1000L)
            runCurrent()

            val errorEvent = awaitItem() as ClientEvent.Error
            assertTrue(errorEvent.reason.message.contains("Characteristic Write Failed"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}