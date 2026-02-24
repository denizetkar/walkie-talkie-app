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
import com.denizetkar.walkietalkieapp.protocol.HandshakeLogic
import com.denizetkar.walkietalkieapp.protocol.Packet
import com.denizetkar.walkietalkieapp.protocol.Protocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            assertTrue(errorEvent.reason.message.contains("Wrong Access Code"))

            cancelAndIgnoreRemainingEvents()
        }
    }
}