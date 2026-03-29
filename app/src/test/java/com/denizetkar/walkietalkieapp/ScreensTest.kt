package com.denizetkar.walkietalkieapp

import android.media.AudioDeviceInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.denizetkar.walkietalkieapp.domain.AppError
import com.denizetkar.walkietalkieapp.domain.AudioDeviceUi
import com.denizetkar.walkietalkieapp.domain.DiscoveredGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `JoinGroupScreen - Displays groups and validates Access Code`() {
        var joinedGroup: DiscoveredGroup? = null
        var enteredCode: String? = null

        val mockGroup = DiscoveredGroup("AA:BB", 0xABCDEF12u, "Hiking", -50, 1u, 2u)

        composeTestRule.setContent {
            JoinGroupScreen(
                discoveredGroups = listOf(mockGroup),
                isJoining = false,
                joinError = null,
                isBluetoothEnabled = true,
                onJoin = { group, code ->
                    joinedGroup = group
                    enteredCode = code
                },
                onJoinErrorAck = {}
            )
        }

        // 1. Verify group is displayed and click join
        composeTestRule.onNodeWithText("Hiking").assertIsDisplayed()
        composeTestRule.onNodeWithStringId(R.string.join_group_nearby_groups_id, "ABCDEF12", substring = true, ignoreCase = true).assertIsDisplayed()
        composeTestRule.onNodeWithStringId(R.string.join_group_nearby_groups_join).performClick()

        // 2. Verify dialog appears but Connect is disabled initially
        composeTestRule.onNodeWithStringId(R.string.join_group_enter_code_title).assertIsDisplayed()
        val connectButton = composeTestRule.onNodeWithStringId(R.string.join_group_enter_code_connect)
        connectButton.assertIsNotEnabled()

        // 3. Enter incomplete code (3 digits)
        composeTestRule.onNodeWithStringId(R.string.join_group_enter_code_label).performTextInput("123")
        connectButton.assertIsNotEnabled()

        // 4. Enter valid code (4 digits) and submit
        composeTestRule.onNodeWithStringId(R.string.join_group_enter_code_label).performTextInput("4") // Appends "4" to "123"
        connectButton.assertIsEnabled()
        connectButton.performClick()

        // 5. Assert callback was fired correctly
        assertEquals("Hiking", joinedGroup?.name)
        assertEquals("1234", enteredCode)
    }

    @Test
    fun `JoinGroupScreen - Displays Bluetooth disabled warning`() {
        composeTestRule.setContent {
            JoinGroupScreen(
                discoveredGroups = emptyList(),
                isJoining = false,
                joinError = null,
                isBluetoothEnabled = false,
                onJoin = { _, _ -> },
                onJoinErrorAck = {}
            )
        }

        composeTestRule.onNodeWithStringId(R.string.join_group_nearby_groups_no_bluetooth).assertIsDisplayed()
    }

    @Test
    fun `RadioScreen - Push To Talk Button triggers Start and Stop`() {
        var isTalking = false

        composeTestRule.setContent {
            RadioScreen(
                groupName = "Hiking",
                accessCode = "1234",
                peerCount = 1, // Network is ready
                isBluetoothEnabled = true,
                availableAudioDevices = emptyList(),
                selectedAudioDevice = 0,
                onDeviceSelect = {},
                onLeave = {},
                onTalkStart = { isTalking = true },
                onTalkStop = { isTalking = false }
            )
        }

        // 1. Initial State
        val pttButtonText = composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_hold_to_talk)
        pttButtonText.assertIsDisplayed()

        // 2. Press Down (Simulate holding the button)
        pttButtonText.performTouchInput { down(center) }
        composeTestRule.waitForIdle() // Allow recomposition

        assertTrue("onTalkStart should have fired", isTalking)
        composeTestRule.onNodeWithText("TALKING").assertIsDisplayed()

        // 3. Release
        composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_talking).performTouchInput { up() }
        composeTestRule.waitForIdle() // Allow recomposition

        assertFalse("onTalkStop should have fired", isTalking)
        composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_hold_to_talk).assertIsDisplayed()
    }

    @Test
    fun `RadioScreen - PTT is disabled if 0 peers are online`() {
        var talkAttempted = false

        composeTestRule.setContent {
            RadioScreen(
                groupName = "Hiking",
                accessCode = "1234",
                peerCount = 0, // Network is disconnected / lonely
                isBluetoothEnabled = true,
                availableAudioDevices = emptyList(),
                selectedAudioDevice = 0,
                onDeviceSelect = {},
                onLeave = {},
                onTalkStart = { talkAttempted = true },
                onTalkStop = {}
            )
        }

        // 1. Initial State should show "SEARCHING…" instead of "HOLD TO TALK"
        val searchingText = composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_searching)
        searchingText.assertIsDisplayed()

        // 2. Try to click it
        searchingText.performClick()

        // 3. Assert no action was triggered
        assertFalse("Talk should not be permitted with 0 peers", talkAttempted)
    }

    @Test
    fun `CreateGroupScreen - Validates input, displays error dialog, and fires callback`() {
        var createdName = ""
        var createdCode = ""
        var errorAcked = false

        composeTestRule.setContent {
            CreateGroupScreen(
                onCreate = { name, code ->
                    createdName = name
                    createdCode = code
                },
                error = AppError.BluetoothRadioUnavailable, // Strongly Typed Error
                onErrorAck = { errorAcked = true }
            )
        }

        // 1. Error Dialog checks the localized string
        composeTestRule.onNodeWithStringId(R.string.error_bt_radio).assertIsDisplayed()
        composeTestRule.onNodeWithStringId(R.string.create_group_alert_button).performClick()
        assertTrue("Error acknowledgment callback should fire", errorAcked)

        // 2. Input Validation
        val goLiveBtn = composeTestRule.onNodeWithStringId(R.string.create_group_go_live)
        goLiveBtn.assertIsNotEnabled()

        composeTestRule.onNodeWithStringId(R.string.create_group_group_name).performTextInput("Hiking")

        // Button is enabled because the code field is empty (valid)
        goLiveBtn.assertIsEnabled()

        // 3. Test Code Input Validation
        composeTestRule.onNodeWithStringId(R.string.create_group_code_placeholder).performTextInput("123")
        // Button should be disabled because code is incomplete (3 digits)
        goLiveBtn.assertIsNotEnabled()

        composeTestRule.onNodeWithStringId(R.string.create_group_code_placeholder).performTextInput("4")
        // Button should be enabled because code is complete (4 digits)
        goLiveBtn.assertIsEnabled()

        goLiveBtn.performClick()

        assertEquals("Hiking", createdName)
        assertEquals("1234", createdCode)
    }

    @Test
    fun `AudioDeviceSelector - Expands dropdown and selects item`() {
        var selectedId = -1
        val devices = listOf(
            AudioDeviceUi(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "", "Wired Headset"),
            AudioDeviceUi(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", "Bluetooth Speaker")
        )

        composeTestRule.setContent {
            AudioDeviceSelector(
                label = "Audio Route",
                devices = devices,
                selectedId = 1,
                onSelect = { selectedId = it }
            )
        }

        composeTestRule.onNodeWithText("Wired Headset").assertIsDisplayed()

        // OutlinedTextField and the Box overlay both have click actions.
        // We grab the last one (the Box overlay) to trigger the dropdown.
        val clickables = composeTestRule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()

        // Assert Dropdown options are visible
        composeTestRule.onNodeWithStringId(R.string.audio_device_selector_default).assertIsDisplayed()
        composeTestRule.onAllNodesWithStringId(R.string.audio_device_wired_headset).onFirst().assertIsDisplayed()

        // Select the other device using the localized resolution
        composeTestRule.onNodeWithStringId(R.string.audio_device_bt_sco, "AA:BB").performClick()

        // Assert callback
        assertEquals(2, selectedId)
    }

    // Splitting the Utility Screens into isolated tests prevents them from
    // pushing each other out of the viewport (fillMaxSize)
    @Test
    fun `UtilityScreens - ServiceErrorScreen displays and fires callback`() {
        var retryClicked = false
        composeTestRule.setContent { ServiceErrorScreen(onRetry = { retryClicked = true }) }
        composeTestRule.onNodeWithStringId(R.string.service_error_screen_title).assertIsDisplayed()
        composeTestRule.onNodeWithStringId(R.string.service_error_screen_button).performClick()
        assertTrue(retryClicked)
    }

    @Test
    fun `UtilityScreens - PermissionRequiredScreen displays and fires callback`() {
        var grantClicked = false
        composeTestRule.setContent { PermissionRequiredScreen(onGrantClick = { grantClicked = true }) }
        composeTestRule.onNodeWithStringId(R.string.permission_required_title).assertIsDisplayed()
        composeTestRule.onNodeWithStringId(R.string.permission_required_button).performClick()
        assertTrue(grantClicked)
    }

    @Test
    fun `UtilityScreens - LoadingScreen displays`() {
        composeTestRule.setContent { LoadingScreen(message = "Starting Engine") }
        composeTestRule.onNodeWithText("Starting Engine").assertIsDisplayed()
    }

    @Test
    fun `RadioScreen - PTT shows BLUETOOTH OFF when disabled`() {
        var talkAttempted = false

        composeTestRule.setContent {
            RadioScreen(
                groupName = "Hiking",
                accessCode = "1234",
                peerCount = 1,
                isBluetoothEnabled = false, // Simulate disabled Bluetooth
                availableAudioDevices = emptyList(),
                selectedAudioDevice = 0,
                onDeviceSelect = {},
                onLeave = {},
                onTalkStart = { talkAttempted = true },
                onTalkStop = {}
            )
        }

        val btn = composeTestRule.onNodeWithStringId(R.string.radio_ptt_ble_off)
        btn.assertIsDisplayed()
        btn.performClick()

        assertFalse("Talk should not be permitted when BT is off", talkAttempted)
    }
}
