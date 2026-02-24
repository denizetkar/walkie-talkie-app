package com.denizetkar.walkietalkieapp.bluetooth

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
@RoboConfig(sdk = [33], application = MainApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GattServerHandlerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
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
    private val mockService = mockk<BluetoothGattService>()

    @Before
    fun setup() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager

        val callbackSlot = slot<BluetoothGattServerCallback>()
        every { bluetoothManager.openGattServer(any(), capture(callbackSlot)) } returns mockGattServer

        every { mockGattServer.notifyCharacteristicChanged(any(), any(), any(), any()) } returns BluetoothStatusCodes.SUCCESS
        every { bluetoothManager.getConnectionState(any(), any()) } returns BluetoothProfile.STATE_DISCONNECTED
        every { mockGattServer.getService(Config.APP_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(Config.CHAR_CONTROL_UUID) } returns controlChar

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
    fun `Security - Zombie Connection Fuse Disconnects Unauthenticated Client`() = testScope.runTest {
        serverHandler.serverEvents.test {
            gattCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertTrue(awaitItem() is ServerEvent.ClientConnected)

            advanceTimeBy(Config.BLE_CONNECT_TIMEOUT + 500L)
            runCurrent()

            verify { mockGattServer.cancelConnection(device) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}