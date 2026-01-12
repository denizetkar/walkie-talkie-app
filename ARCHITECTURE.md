# 🏗️ Reactive Mesh Architecture (v2.0)

This document defines the **Ideal State** architecture for the Walkie-Talkie application. It prioritizes **Correctness**, **Thread Safety**, and **Testability**.

---

## 1. Architectural Philosophy

### A. The "Pure Core" Principle
The business logic (`MeshController`) must be a **Pure System**.
*   **Input:** Events (User clicked button, Packet arrived, Timer ticked).
*   **Output:** State Snapshots & Intents (Target Topology, Audio Gate Status).
*   **Constraint:** The Core never calls Android APIs directly. It never blocks. It is fully unit-testable on a JVM desktop environment.

### B. Unidirectional Data Flow (UDF)
Data flows in a single loop, never cross-linked.
1.  **Events** flow UP from Drivers/UI.
2.  **The Actor** processes Events sequentially.
3.  **State** flows DOWN to Drivers/UI.
4.  **Drivers** reconcile the difference between *Current State* and *Desired State*.

### C. Supervision Hierarchies (Fault Tolerance)
We utilize **Structured Concurrency** to define failure domains.
*   **App Scope:** The lifecycle of the Application.
*   **Service Scope:** The lifecycle of the Foreground Service.
*   **Driver Scope:** Managed by the Service. Can be restarted if the Bluetooth stack freezes.
*   **Peer Scope:** Managed by the Driver. Can be restarted if a specific connection errors.

---

## 2. The Control Plane (The "Brain")

### The Mesh Actor
The central logic unit is an Actor that consumes a sealed class `Action`.

```kotlin
sealed class Action {
    data class UserIntent(val type: IntentType) : Action()
    data class NetworkEvent(val payload: Packet) : Action()
    data class SystemEvent(val type: SysType) : Action()
}
```

*   **Concurrency:** A single coroutine consumes the `Action` channel. This eliminates `Mutex` and race conditions entirely.
*   **State Production:** The Actor emits an immutable `AppState` object.

### The "Diff Engine" (Topology Control)
The Controller does not issue commands like `connect()` or `disconnect()`.
Instead, it calculates a **Desired Topology**.

*   **Output:** `Flow<Set<NodeId>>` (The list of peers we *should* be connected to).
*   **Logic:** The BLE Driver observes this flow.
    *   `Target - Current` = **Connect** (Launch Job).
    *   `Current - Target` = **Disconnect** (Cancel Job).
    *   This makes the system **Idempotent** and self-healing.

---

## 3. The Network Plane (The "Muscle")

### The "Bouncer" (Security Middleware)
The Network Layer is divided into two strict zones.
1.  **Zone A (Untrusted):** Raw GATT connections. The "Bouncer" runs the Handshake/Auth state machine here.
2.  **Zone B (Trusted):** Authenticated Transport. Only peers that pass the Bouncer are exposed to the `MeshController`.

### Protocol: Controlled Flooding
Since this is a broadcast application, we use a flooding strategy with deduplication.
1.  **Audio Packet:** Received by Driver. Passed to Controller.
2.  **Logic (Core):** Hash of packet checked against `SeenPackets` cache.
    *   *If Seen:* Ignore.
    *   *If New:* Update Cache, Emit `Relay` Effect.
3.  **Driver:** Receives `Relay` Effect and broadcasts to all other connected peers.

### QoS Pipeline (Traffic Shaping)
To prevent "Buffer Bloat" (old audio playing seconds later), we implement a Priority Funnel at the Driver level.
*   **High Priority (Control):** Unlimited Queue, Reliable Write.
*   **Low Priority (Audio):** Ring Buffer (Size ~4). Newest audio overwrites oldest audio *before* transmission if the BLE radio is busy.

---

## 4. The Hardware Plane (Resource Gating)

Hardware resources are guarded by **Reactive Gates** that physically prevent access until dependencies are met.

### The Capability Chain
Drivers do not "try" to access hardware; they observe a `Gate` signal.

1.  **Permission Gate:** `Flow<Boolean>` (Are permissions granted?)
2.  **Service Gate:** `Flow<Boolean>` (Is Foreground Service active?)
3.  **Audio Gate:** `combine(Permission, Service)` -> `Flow<Boolean>`

*   **Logic:** The Audio Engine observes `AudioGate`. When `true`, it opens the mic. When `false`, it closes immediately. This prevents the `SecurityException` crash by design.

### Derived Hardware Modes
Battery usage is a pure function of State.
*   `State.Idle` -> **Radio Off**
*   `State.Joining` -> **Scan(Aggressive)**
*   `State.Linked` -> **Scan(Passive) + Advertise(Periodic)**

---

## 5. Wire Protocol (Optimized v2.0)

Since we are breaking compatibility, we define a compact binary protocol optimized for BLE MTUs.

### Packet A: Service Data (10 Bytes)
Broadcasted in Advertising Packets.
*   `[0-3] NodeID` (My Identity)
*   `[4-7] NetID` (Root Identity)
*   `[8]   Hops` (Distance to Root)
*   `[9]   Flags` (Bit 0: Available for connection)

### Packet B: Control (Reliable)
*   `[0]   OpCode`
*   `[1-N] Payload`

**OpCodes:**
*   `0x01` **Hello** (Triggers Challenge)
*   `0x02` **Auth** (Challenge/Response)
*   `0x03` **Heartbeat** (NetID, Seq, Hops)

### Packet C: Audio (Unreliable)
*   `[0-3] OriginID` (Who spoke this? Required for relaying)
*   `[4-5] SeqNum` (For Jitter Buffer ordering)
*   `[6-N] OpusData` (Encoded Voice)

---

## 6. Testing Strategy

### Unit Tests (The Core)
Because the `MeshController` is pure:
1.  Feed `Action.UserIntent(CreateGroup)`
2.  Feed `Action.NetworkEvent(Discovery)`
3.  **Assert** `AppState.targetPeers` contains the discovered node.
4.  *No mocks of Android Bluetooth classes required.*

### Integration Tests (The Drivers)
We use a "Virtual Driver" pattern.
1.  Replace `BleDriver` with `MockDriver` that simulates peers appearing/disappearing.
2.  Verify the `Reconciliation Loop` correctly triggers connection attempts based on State changes.

---

## 7. Implementation Strategy (The "Glue")

### A. The Reactive Bridge (FFI & Callbacks)
Since Rust and System APIs use callbacks (often on background threads), we use the `callbackFlow` pattern to bridge them into the Reactive World.
*   **Pattern:** `fun startEventStream(): Flow<Event> = callbackFlow { ... }`
*   **Rule:** No component outside the `callbackFlow` builder should ever touch a raw callback. This ensures all events entering the system are suspended properly and respect backpressure.

### B. Event Aggregation (The "Funnel")
The Mesh Controller (Actor) does not manually subscribe to individual components. Instead, the `WalkieTalkieService` performs **Stream Merging**.
1.  **Driver Output:** `val networkEvents: Flow<Action.NetworkEvent>`
2.  **UI Output:** `val userIntents: Flow<Action.UserIntent>`
3.  **Aggregation:** `val inputStream = merge(networkEvents, userIntents, systemEvents)`
4.  **Consumption:** `actor.process(inputStream)`
*   **Benefit:** The Controller is completely decoupled from the number or type of event sources.

### C. Wiring & Cyclic Dependency Resolution
To avoid "Component A needs B, and B needs A," we use **Flow Injection**.
*   **The Service:** Owns the `MutableStateFlow<AppState>`.
*   **The Controller:** Takes `(currentState: StateFlow, eventSink: Channel)`.
*   **The Driver:** Takes `(targetTopology: Flow<Set<NodeId>>)` and `(session: Flow<Session>)`.
*   **Result:** Components depend on *Data Streams*, not on each other.

### D. Dispatcher Domains
To ensure UI smoothness and extensive I/O safety:
1.  **Logic Domain (`Dispatchers.Default`):** The Actor Loop. Pure CPU tasks (Hashing, State Diffing).
2.  **I/O Domain (`Dispatchers.IO`):** BLE Operations, File I/O, Rust FFI calls.
3.  **Main Domain (`Dispatchers.Main`):** Only strictly restricted to the UI layer (Compose) observing the StateFlow.
