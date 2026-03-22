# 🏛️ Reactive Mesh Architecture (The Ideal)

This document defines the architectural standard for the Walkie-Talkie application. It describes a **Purely Reactive, Event-Driven System** designed for correctness, determinism, and self-healing properties.

---

## 1. The Core Philosophy

### A. The "Cycle of Life" (Unidirectional Data Flow)
The system operates in a single, infinite, unidirectional loop. Data never flows "backwards".

1.  **Events (Inputs):** The world changes (User clicks button, Packet arrives, Time passes).
2.  **Reducer (Logic):** A pure function calculates the new reality (`State + Event -> NewState`).
3.  **State (SSOT):** An immutable snapshot of the entire system.
4.  **Reconciliation (Drivers):** The "Shell" observes the State and forces the Hardware to match it.

### B. Functional Core, Imperative Shell
*   **The Core (`MeshController`):** Is effectively a mathematical formula. It has **zero dependencies** on Android, Bluetooth, or Time. It is 100% unit-testable in isolation.
*   **The Shell (`Drivers`):** Dirty, chaotic, platform-specific code. Its only job is to bridge the gap between the *Ideal State* (Core) and the *Real World* (Hardware).

---

## 2. The Functional Core (The Brain)

The Core is modeled as a Finite State Machine (FSM).

### The State (`AppState`)
The State is the **Single Source of Truth**. If it isn't in `AppState`, it doesn't exist.
*   **Derived Properties:** We prefer derived data over cached data to prevent de-synchronization.
    *   *Bad:* `val isAdvertising: Boolean`
    *   *Good:* `val isAdvertising: Boolean get() = session != null`
*   **Strongly Typed Constraints:** Error messaging is never passed as strings from the drivers. Errors are encoded as a `sealed interface AppError`. Translation and interpretation belong entirely in the UI presentation layer.

### The Reducer
Logic is implemented as a pure function:
```kotlin
fun reduce(currentState: AppState, action: Action): Transition {
    // Returns (NewState, Set<SideEffect>)
}
```
*   **Constraint:** The Reducer never blocks. It never waits. It never calls `System.currentTimeMillis()` (Time is an input Action).

---

## 3. The Reactive Drivers (The Nervous System)

Drivers are **State Reconcilers**, not Command Executors. This distinction is vital for self-healing.

### Imperative vs. Reactive (Why we choose Reactive)
*   *Imperative (Bad):* Controller sends `CMD_CONNECT(A)`. If the connection fails, the Controller must handle the error and retry. Logic bleeds into the Core.
*   *Reactive (Ideal):*
    1.  Controller updates State: `desiredPeers = {A}`.
    2.  Driver observes State. Sees `connected = {}`. Diff = `+A`.
    3.  Driver initiates connection.
    4.  If connection fails, Driver sees `desired={A}, connected={}` and **retries automatically**.
    5.  The Core is oblivious to the struggle.

### The Reconciliation Loops
1.  **Network Reconciler:**
    *   *Input:* `state.desiredTopology` (Who we WANT to be connected to).
    *   *Reality:* `driver.connectedPeers`.
    *   *Action:* Connect/Disconnect until `Reality == Input`.
2.  **Audio Reconciler:**
    *   *Input:* `state.audioConfig` (Mic Enabled? Which Device?).
    *   *Reality:* Audio Engine Status.
    *   *Action:* Start/Stop/Reconfigure Oboe stream.
3.  **UI Reconciler:**
    *   *Input:* `state`.
    *   *Action:* Jetpack Compose (The ultimate View Reconciler).

---

## 4. Side Effects (The Output)

While State covers *persistent* configurations (Connections, Advertising), we need a mechanism for *transient* events (sending a packet).

### The Effect Stream
*   **Definition:** Fire-and-forget commands emitted by the Core.
*   **Scope:** Strictly limited to things that cannot be modeled as State.
    *   *Valid Effect:* `TransmitPacket(Payload)` (This is an event in time).
    *   *Valid Effect:* `ShowToast(R.string.error, AppLanguage)` (Trigger UI feedback using strongly-typed resource IDs).
    *   *Invalid Effect:* `StartScanning` (This should be `state.isScanning = true`).

---

## 5. Security & Protocol

### Zero-Trust "Bouncer" Model
The Core Logic operates in a **Trusted Zone**. The Network Driver operates in an **Untrusted Zone**.
*   **The Boundary:** The `HandshakeLogic`.
*   **Rule:** No peer exists in the `AppState` until it has cryptographically proven its identity via the Challenge-Response handshake.
*   **Benefit:** The Core never processes malformed or unauthenticated topology updates.

### Wire Protocol (Binary Efficiency)
We optimize for BLE's small MTU (23-512 bytes).
*   **Service Data (Advertising):** 10 Bytes. Contains strictly enough info for the "Reconciler" to decide if a connection is needed.
*   **Control Packets:** Reliable. Used for Topology (Heartbeats) and Security.
*   **Audio Packets:** Unreliable (Fire-and-forget). Raw Opus frames with a minimal 6-byte header for jitter buffering.

---

## 6. Directory Structure (The Map)

```text
├── domain/            # The Pure World
│   ├── State.kt       # The Data structures
│   ├── Actions.kt     # The Events
│   └── AppError.kt    # The typed failure states
├── logic/             # The Brain
│   ├── Reducer.kt     # The Pure Logic Function
│   └── Actor.kt       # The Runtime (Coroutines)
├── network/           # The Bluetooth Implementation
│   ├── driver/        # The Reconcilers
│   └── protocol/      # Binary Serializers
└── ui/                # The View Layer
```