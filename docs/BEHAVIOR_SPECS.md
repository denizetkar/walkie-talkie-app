# 🧪 Behavioral Specifications

This document defines the expected behavior of the app from the user's perspective.
**Use this as a checklist for manual testing.**

---

## 1. Onboarding & Group Selection

### 🟢 Happy Path: Host creates a group
*   **Given** I am on the home screen
*   **When** I create a group named "Summit"
*   **Then** I immediately enter the "Radio" screen
*   **And** I see a 4-digit code (e.g., "9988")
*   **And** The peer count is "0 Peers" (I am alone)

### 🟢 Happy Path: User joins a group
*   **Given** My friend is hosting "Summit" (Code: 9988)
*   **When** I tap "Summit" on the Join screen and enter "9988"
*   **Then** I enter the "Radio" screen
*   **And** My peer count becomes "1 Peer"
*   **And** My friend's peer count becomes "1 Peer"

### 🟡 Edge Case: Name Collision (The "Key" Logic)
*   **Given** Group A is hosting "Camp" (Code: 1111)
*   **And** Group B is hosting "Camp" (Code: 2222) nearby
*   **When** I scan for groups
*   **Then** I see only **ONE** entry for "Camp" in the list
*   **When** I enter code "2222"
*   **Then** There is no guarantee that I successfully join Group B

### 🔴 Negative Case: Wrong Code
*   **Given** My friend is hosting "Summit" (Code: 9988)
*   **When** I enter "0000"
*   **Then** The app shows an error "Authentication Failed"
*   **And** I remain on the Join screen

### 🔴 Negative Case: Ghost Group (Timeout)
*   **Given** A group "OldCamp" shut down 1 minute ago
*   **But** My phone still has it cached in the list
*   **When** I try to join "OldCamp"
*   **Then** The app shows "Connecting..." for 15 seconds
*   **And** Returns to the list with "Connection Timed Out"
*   **And** "OldCamp" is removed from the list

---

## 2. The Radio Experience (Audio)

### 🟢 Happy Path: Talking (PTT)
*   **Given** I am connected to 1 peer
*   **When** I hold the "Talk" button
*   **Then** The button turns Red
*   **And** My friend hears my voice clearly

### 🟢 Happy Path: Receiving
*   **Given** I am connected
*   **When** My friend talks
*   **Then** My audio plays through the speaker
*   **And** My volume buttons adjust the loudness of their voice

### 🟡 Edge Case: The "Loner"
*   **Given** I am in a group but "0 Peers" are online
*   **Then** I cannot press the "Talk" button

### 🟡 Edge Case: Double Talk (Collision)
*   **Given** Peer A and Peer B press talk at the exact same moment
*   **When** They both speak
*   **Then** Peer C hears both voices mixed correctly together
*   **And** The app does **NOT** crash or disconnect

### 🟡 Edge Case: The "Chirp" (Short Press)
*   **Given** I accidentally tap the Talk button for a split second
*   **Then** The button flashes Red briefly
*   **And** The app handles the rapid start/stop sequence without crashing

---

## 3. Mesh Mechanics (Movement)

### 🟢 Happy Path: The Relay (Daisy Chain)
*   **Given** I am standing next to Bob, and Bob is standing next to Charlie
*   **And** I am too far from Charlie to connect directly
*   **When** I talk
*   **Then** Bob hears me
*   **And** Charlie hears me (relayed via Bob)

### 🟢 Happy Path: The Merge
*   **Given** Two groups ("TeamA", Code 1234) formed separately
*   **When** They walk within range of each other
*   **Then** They automatically merge into one big group
*   **And** Everyone's peer count updates

### 🟡 Edge Case: The "Yo-Yo" (Range Testing)
*   **Given** I am connected to Bob
*   **When** I walk away until "0 Peers" shows
*   **And** I immediately walk back into range
*   **Then** The app reconnects automatically within a few seconds
*   **And** I can talk again

---

## 4. Hardware & System Resilience

### 🟢 Happy Path: Background Mode
*   **Given** I am in a group
*   **When** I press the Power button (Screen OFF)
*   **And** Put the phone in my pocket
*   **Then** I continue to hear incoming audio
*   **And** My connection stays alive

### 🟢 Happy Path: Headphone Swap
*   **Given** I am listening on the loudspeaker
*   **When** I plug in wired headphones
*   **Then** Audio moves to the headphones instantly
*   **When** I unplug them
*   **Then** Audio moves back to the loudspeaker

### 🟡 Edge Case: GSM Interruption
*   **Given** I am in a live session
*   **When** My mom calls me (Phone Ringing)
*   **Then** The Walkie-Talkie audio silences immediately
*   **When** I answer the call
*   **Then** I cannot use the PTT button
*   **When** I hang up
*   **Then** The Walkie-Talkie session is still active and audio resumes

### 🔴 Negative Case: Permission Revoked
*   **Given** I am in a session
*   **When** I go to Android Settings and revoke "Microphone" permission
*   **And** I return to the app
*   **Then** The app shows a warning (Toast/Dialog)
*   **And** Pressing Talk does nothing (or prompts for permission)
*   **And** The app does NOT crash

### 🔴 Negative Case: Bluetooth Kill
*   **Given** I am connected to peers
*   **When** I swipe down system settings and turn Bluetooth OFF
*   **Then** The app leaves the group
*   **And** I'm back to the join screen
*   **When** I turn Bluetooth back ON
*   **Then** Groups start appearing
*   **And** I can join any group
*   **And** Talking/listening works