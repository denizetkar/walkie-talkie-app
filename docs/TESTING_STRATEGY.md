# 🛡️ Testing Strategy & Quality Assurance

This document defines the architectural standards for ensuring the reliability of the Walkie-Talkie app.
Our strategy relies on the **Humble Object Pattern**: We isolate complex logic into pure, testable modules ("The Brain") and keep the platform-specific code ("The Muscles") as thin as possible.

---

## 1. The Testing Pyramid

We aim for a high degree of confidence without requiring a fleet of physical devices for every code change.

| Layer | Target Component | Technology | Scope | Run On |
| :--- | :--- | :--- | :--- | :--- |
| **L1: Core Logic** | `MeshController`, `Protocol` | **JUnit 5** + Coroutines Test | Topology, Routing, State Machine. | **Host (JVM)** |
| **L2: Audio Math** | `audio_core.rs` | **Rust** (`cargo test`) | Jitter Buffer, Packet Loss Concealment (PLC), OpCode wrapping. | **Host (Native)** |
| **L3: Integration** | `BleDriver`, `VoiceManager` | **Android Instrumented** | Permissions, Service Binding, FFI Bridge. | **Device / Emulator** |
| **L4: Field** | Full App | **Manual** | Range, Latency, Battery, Interference. | **Physical World** |

---

## 2. L1: The Mesh Simulator (Kotlin/JVM)

We test the mesh algorithms by simulating a "Virtual Network" on the JVM. We do **not** mock Android Bluetooth classes. Instead, we test the `MeshController` which is purely reactive (`Action` -> `State` + `Effect`).

### Key Scenarios
1.  **Convergence (Merging):**
    *   *Setup:* Local Node (ID 10) is Root.
    *   *Action:* Receive `Heartbeat(NetID: 20, Seq: 5)`.
    *   *Assert:* Local State updates to `NetworkTopology.Mesh(Root=20)`.
    *   *Assert:* `Effect.Transmit` is emitted (Relaying the better root).
2.  **Split Horizon (Loop Prevention):**
    *   *Action:* Receive packet from Peer A.
    *   *Assert:* `Effect.Transmit` is emitted with `excludedSource = Peer A`.
3.  **Self-Healing (Timeouts):**
    *   *Setup:* Connected to Mesh.
    *   *Action:* `testScheduler.advanceTimeBy(3000ms)` (Heartbeat Timeout).
    *   *Assert:* State reverts to `Standalone`.
4.  **Deduplication:**
    *   *Action:* Receive Packet X twice.
    *   *Assert:* `Effect.RenderAudio` is emitted only once.

---

## 3. L2: The Audio Engine (Rust/Host)

To ensure high-quality audio, we must verify the Jitter Buffer and PLC logic. We refactor the Rust code to separate **Audio Logic** (`audio_core`) from **Android IO** (`oboe`).

**Target:** `cargo test` (Runs on your dev machine, not Android).

### Key Scenarios
1.  **Jitter Reordering:**
    *   *Input:* `[Seq 3, Seq 1, Seq 2]`
    *   *Output:* `[Seq 1, Seq 2, Seq 3]`
2.  **Packet Loss Concealment (PLC):**
    *   *Input:* `[Seq 1, Seq 3]` (Seq 2 missing).
    *   *Output:* `[Seq 1, (Silence/Interpolation), Seq 3]`.
3.  **Underrun Recovery:**
    *   *Scenario:* Buffer drains completely.
    *   *Assert:* Engine enters `Buffering` state and waits for `JITTER_BUFFER_START_THRESHOLD` packets before resuming playback.
4.  **Protocol Wrapping:**
    *   *Verify:* `wrap_packet(ID, Seq, Data)` creates the correct binary header structure.

---

## 4. L3: Android Integration (Device)

These tests verify that the "Muscles" (Drivers) are correctly wired to the System.

### Key Scenarios
1.  **Service Lifecycle:**
    *   Start Service -> Verify `WakeLock` acquired.
    *   Stop Service -> Verify `WakeLock` released.
2.  **Permission Flow:**
    *   Grant Permissions -> Verify `BleDriver` starts scanning.
    *   Deny Permissions -> Verify `BleDriver` stops and UI shows error.
3.  **UniFFI Bridge:**
    *   Call `VoiceManager.start()` -> Verify Rust `init_logger` is called without crashing.

---

## 5. L4: Manual Field Verification

Since BLE hardware varies significantly by manufacturer (Samsung vs Pixel vs Xiaomi), automated tests cannot catch RF quirks.

### The "Faraday" Test (Reconnection)
1.  **Connect** two devices. Verify audio works.
2.  **Separate** them physically until audio cuts out (simulating signal loss).
3.  **Return** to range.
4.  **Verify** audio resumes automatically within 5 seconds without user intervention.

### The "Airplane" Test (System Events)
1.  During a live session, toggle **Airplane Mode ON**.
2.  Verify app shows "Bluetooth Disabled" or "Disconnected".
3.  Toggle **Airplane Mode OFF**.
4.  Verify app re-advertises and reconnects automatically.

### The "Pocket" Test (CPU Management)
1.  Start a session.
2.  Turn **Screen OFF**.
3.  Put phone in pocket. Wait 10 minutes (allow Android Doze mode to kick in).
4.  **Verify** incoming audio is still heard (confirms `WakeLock` and Foreground Service are working).

### The "Group Relay" Test (Multi-Hop)
1.  **Setup:** Device A --(Range)-- Device B --(Range)-- Device C.
2.  **Constraint:** Device A cannot see Device C directly.
3.  **Action:** Device A talks.
4.  **Verify:** Device C hears Device A (via Device B).