# 📝 Implementation Roadmap (Reactive v2.0)

## Phase 0: The Domain Language (Zero Logic)
*Goal: Define the vocabulary. Pure Kotlin.*

- [ ] **Package `com.denizetkar.walkietalkieapp.domain`**:
    - [ ] `Action.kt`:
        -   `NetworkEvent.PacketReceived(data, rssi)`: Driver -> Core.
        -   `SystemEvent.BluetoothState(isEnabled)`: System -> Core.
    - [ ] `Effect.kt`:
        -   `NetworkCmd.TransmitPacket(data, strategy)`: Core -> Driver.
        -   `NetworkCmd.SetAdvertising(config)`: Core -> Driver.
    - [ ] `AppState.kt`:
        -   `session: SessionContext?` (AccessCode, MyNodeID).
        -   `seenPackets: Set<Int>` (The Cache).
        -   `advertising: AdvertisingState` (Derived from Topology).
- [ ] **Package `com.denizetkar.walkietalkieapp.protocol`**:
    - [ ] `Packet.kt`: Binary Serializers (v2.0 Layout).

## Phase 1: The Pure Core (The Brain)
*Goal: Testable Logic. No Android.*

- [ ] **Package `com.denizetkar.walkietalkieapp.core`**:
    - [ ] `MeshController` (Actor):
        -   **Logic:** `PacketReceived` -> `if (new)` -> Update Cache + Emit `Effect.Relay`.
        -   **Logic:** `HeartbeatTimer` -> Emit `Effect.Transmit(Heartbeat)`.
        -   **Logic:** `BluetoothState(false)` -> Reset Topology + Stop Advertising.
    - [ ] **Test:** Verify the "Relay" logic. Feed a packet, assert it emits a Relay effect. Feed it again, assert NO effect.

## Phase 2: The Network Plane (The Muscle)
*Goal: Dumb Reconciliation.*

- [ ] **Refactor `BleDriver`**:
    -   **Input 1:** `Flow<Set<NodeId>>` (Connection Targets).
    -   **Input 2:** `Flow<AdvertisingConfig>` (What to broadcast).
    -   **Input 3:** `Flow<SessionContext?>` (Credentials for the Bouncer).
    -   **Input 4:** `Flow<Effect.NetworkCmd>` (Packets to send).
    -   **Output:** `Flow<Action.NetworkEvent>`.
- [ ] **Implement `ConnectionReconciler`**:
    -   Loop: `Targets` vs `Connected`.
    -   **Backoff:** `Map<NodeId, RetryCount>` with exponential delays.
- [ ] **Implement `AdvertiserLoop`**:
    -   Collect `AdvertisingConfig`.
    -   If changed -> Update Android `AdvertisingSet`.

## Phase 3: The Hardware Glue (The Gates)
*Goal: Safety.*

- [ ] **Refactor `VoiceManager`**:
    -   **Strict Input:** `Flow<Boolean>` (AudioGate).
    -   **Output:** `Flow<Action.NetworkEvent>` (Packet from Rust).
    -   **Strategy:** `callbackFlow` using `trySend` (Drop oldest) for incoming Audio to prevent blocking Rust.
- [ ] **System Monitors**:
    -   `BluetoothMonitor`: Emits `Action.SystemEvent.BluetoothState`.

## Phase 4: Integration (The Wiring)
*Goal: Connecting the pipes.*

- [ ] **Refactor `WalkieTalkieService`**:
    -   **Sources:** `merge(driver.events, voice.events, btMonitor.events, ui.intents)`.
    -   **Sinks:**
        -   `controller.state.map { it.topology }` -> `driver.targets`.
        -   `controller.state.map { it.advertising }` -> `driver.config`.
        -   `controller.state.map { it.session }` -> `driver.session`.
        -   `controller.effects.filterIsInstance<NetworkCmd>` -> `driver.cmds`.
    -   **Observation:** This creates a perfectly circular, non-blocking system.

## Phase 5: Cleanup
- [ ] Delete `ARCHITECTURE_LEGACY.md`.
- [ ] Verify `TODO.md` is empty.
