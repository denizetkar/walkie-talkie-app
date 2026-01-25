# 📻 Product Requirements

This document defines the core value proposition and user needs for the Walkie-Talkie application.

## 1. Simplicity & Identity
*   **PR-1 (One Identity):** A Group is visually identified by its **Name**. The "Access Code" is a password, not an identifier.
*   **PR-2 (De-duplication):** If multiple groups named "Camp" exist nearby, the user sees only **one** entry for "Camp". The Access Code is assumed to be the same.
*   **PR-3 (Instant On):** Creating a group should be instantaneous. No accounts, no servers, no loading screens.
*   **PR-4 (Ephemeral):** Sessions are temporary. If the app is force-closed, the group is left. No history is saved.

## 2. Seamless Connectivity
*   **PR-5 (Magic Connection):** Users never manually pair Bluetooth devices. The phone handles all connections.
*   **PR-6 (Range Extension):** Users can talk to friends out of direct sight if other users are positioned in between (Relaying).
*   **PR-7 (Automatic Merging):** If a group splits and reunites, connections restore automatically.
*   **PR-8 (Capacity):** The app must support small groups (e.g., 2-6 people) reliably.

## 3. Reliable Communication
*   **PR-9 (Push-to-Talk):** Pressing the talk button opens the channel immediately. No "dialing" delay.
*   **PR-10 (Feedback):** Users must know if they are "Live" (Red button), "Receiving" (Audio playing), or "Disconnected" (0 Peers).
*   **PR-11 (Volume):** The app respects the system's "Call Volume" (not Media Volume), so users can adjust loudness using hardware buttons.

## 4. Real-World Usability
*   **PR-12 (Pocket Mode):** The app works fully when the screen is locked/off.
*   **PR-13 (Distraction Free):** Incoming GSM calls take priority. Walkie-Talkie audio pauses.
*   **PR-14 (Hardware Ready):** Plugging/unplugging headphones switches audio instantly without ending the session.
*   **PR-15 (Permission Safety):** If permissions are denied at runtime, the app degrades gracefully instead of crashing.