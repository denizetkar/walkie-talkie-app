package com.denizetkar.walkietalkieapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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

        val mockGroup = DiscoveredGroup("AA:BB", "Hiking", -50, 1u)

        composeTestRule.setContent {
            JoinGroupScreen(
                discoveredGroups = listOf(mockGroup),
                isJoining = false,
                joinError = null,
                onJoin = { group, code ->
                    joinedGroup = group
                    enteredCode = code
                },
                onJoinErrorAck = {}
            )
        }

        // 1. Verify group is displayed and click join
        composeTestRule.onNodeWithText("Hiking").assertIsDisplayed()
        composeTestRule.onNodeWithText("Join").performClick()

        // 2. Verify dialog appears but Connect is disabled initially
        composeTestRule.onNodeWithText("Enter Access Code").assertIsDisplayed()
        val connectButton = composeTestRule.onNodeWithText("Connect")
        connectButton.assertIsNotEnabled()

        // 3. Enter incomplete code (3 digits)
        composeTestRule.onNodeWithText("Code").performTextInput("123")
        connectButton.assertIsNotEnabled()

        // 4. Enter valid code (4 digits) and submit
        composeTestRule.onNodeWithText("Code").performTextInput("4") // Appends "4" to "123"
        connectButton.assertIsEnabled()
        connectButton.performClick()

        // 5. Assert callback was fired correctly
        assertEquals("Hiking", joinedGroup?.name)
        assertEquals("1234", enteredCode)
    }

    @Test
    fun `RadioScreen - Push To Talk Button triggers Start and Stop`() {
        var isTalking = false

        composeTestRule.setContent {
            RadioScreen(
                groupName = "Hiking",
                accessCode = "1234",
                peerCount = 1, // Network is ready
                availableMics = emptyList(),
                availableSpeakers = emptyList(),
                selectedMicId = 0,
                selectedSpeakerId = 0,
                onMicSelect = {},
                onSpeakerSelect = {},
                onLeave = {},
                onTalkStart = { isTalking = true },
                onTalkStop = { isTalking = false }
            )
        }

        // 1. Initial State
        val pttButtonText = composeTestRule.onNodeWithText("HOLD TO TALK")
        pttButtonText.assertIsDisplayed()

        // 2. Press Down (Simulate holding the button)
        pttButtonText.performTouchInput { down(center) }
        composeTestRule.waitForIdle() // Allow recomposition

        assertTrue("onTalkStart should have fired", isTalking)
        composeTestRule.onNodeWithText("TALKING").assertIsDisplayed()

        // 3. Release
        composeTestRule.onNodeWithText("TALKING").performTouchInput { up() }
        composeTestRule.waitForIdle() // Allow recomposition

        assertFalse("onTalkStop should have fired", isTalking)
        composeTestRule.onNodeWithText("HOLD TO TALK").assertIsDisplayed()
    }

    @Test
    fun `RadioScreen - PTT is disabled if 0 peers are online`() {
        var talkAttempted = false

        composeTestRule.setContent {
            RadioScreen(
                groupName = "Hiking",
                accessCode = "1234",
                peerCount = 0, // Network is disconnected / lonely
                availableMics = emptyList(),
                availableSpeakers = emptyList(),
                selectedMicId = 0,
                selectedSpeakerId = 0,
                onMicSelect = {},
                onSpeakerSelect = {},
                onLeave = {},
                onTalkStart = { talkAttempted = true },
                onTalkStop = {}
            )
        }

        // 1. Initial State should show "SEARCHING..." instead of "HOLD TO TALK"
        val searchingText = composeTestRule.onNodeWithText("SEARCHING...")
        searchingText.assertIsDisplayed()

        // 2. Try to click it
        searchingText.performClick()

        // 3. Assert no action was triggered
        assertFalse("Talk should not be permitted with 0 peers", talkAttempted)
    }

    @Test
    fun `CreateGroupScreen - Validates input, displays error dialog, and fires callback`() {
        var createdName = ""
        var errorAcked = false

        composeTestRule.setContent {
            CreateGroupScreen(
                onCreate = { createdName = it },
                error = "Name taken", // Test error dialog at the same time
                onErrorAck = { errorAcked = true }
            )
        }

        // 1. Error Dialog
        composeTestRule.onNodeWithText("Name taken").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").performClick()
        assertTrue("Error acknowledgment callback should fire", errorAcked)

        // 2. Input Validation
        val goLiveBtn = composeTestRule.onNodeWithText("Go Live")
        goLiveBtn.assertIsNotEnabled()

        composeTestRule.onNodeWithText("Group Name").performTextInput("Hiking")
        goLiveBtn.assertIsEnabled()
        goLiveBtn.performClick()

        assertEquals("Hiking", createdName)
    }

    @Test
    fun `AudioDeviceSelector - Expands dropdown and selects item`() {
        var selectedId = -1
        val devices = listOf(
            AudioDeviceUi(1, "Wired Headset"),
            AudioDeviceUi(2, "Bluetooth Speaker")
        )

        composeTestRule.setContent {
            AudioDeviceSelector(
                label = "Mic",
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
        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth Speaker").assertIsDisplayed()

        // Select the other device
        composeTestRule.onNodeWithText("Bluetooth Speaker").performClick()

        // Assert callback
        assertEquals(2, selectedId)
    }

    // Splitting the Utility Screens into isolated tests prevents them from
    // pushing each other out of the viewport (fillMaxSize)
    @Test
    fun `UtilityScreens - ServiceErrorScreen displays and fires callback`() {
        var retryClicked = false
        composeTestRule.setContent { ServiceErrorScreen(onRetry = { retryClicked = true }) }
        composeTestRule.onNodeWithText("Radio Service Failed to Start").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue(retryClicked)
    }

    @Test
    fun `UtilityScreens - PermissionRequiredScreen displays and fires callback`() {
        var grantClicked = false
        composeTestRule.setContent { PermissionRequiredScreen(onGrantClick = { grantClicked = true }) }
        composeTestRule.onNodeWithText("Permissions Needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant Permissions").performClick()
        assertTrue(grantClicked)
    }

    @Test
    fun `UtilityScreens - LoadingScreen displays`() {
        composeTestRule.setContent { LoadingScreen(message = "Starting Engine") }
        composeTestRule.onNodeWithText("Starting Engine").assertIsDisplayed()
    }
}