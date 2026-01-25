# ⚙️ Engineering Internals

This document covers the implementation details of the Rust Audio Engine, FFI strategy, and Testing methodologies.

---

## 1. The Rust Audio Engine

We use **Rust** for the audio path to guarantee low latency and memory safety without the garbage collection pauses of the JVM.

### Components
*   **Oboe (Google):** Provides the high-performance audio stream (AAudio/OpenSL ES).
*   **Opus (Xiph):** Efficient voice codec (48kHz, VOIP mode).
*   **Jitter Buffer (`BTreeMap`):**
    *   Used on the *Receiver* side.
    *   Orders incoming packets by `SeqNum`.
    *   Handles packet loss (PLC) and out-of-order delivery.

### The "Hot Mic" Architecture
To achieve instant Push-to-Talk (PTT):
1.  **Always On:** The Audio Input stream starts immediately when the user joins a group.
2.  **Soft Gate:** A Rust `AtomicBool` acts as the gate.
    *   **Open:** Packets are encoded and sent to Kotlin.
    *   **Closed:** Audio is captured but discarded (silence).
3.  **Benefit:** Eliminates the ~300ms hardware "warm-up" latency when the user presses the button.

---

## 2. FFI Strategy (UniFFI)

We use **Mozilla UniFFI** to generate the bindings between Kotlin and Rust.

### Data Flow
1.  **Kotlin -> Rust:** Method calls on the `AudioEngine` object (e.g., `start_session`, `set_mic_enabled`).
2.  **Rust -> Kotlin:** Callback interfaces.
    *   `PacketTransport`: Rust calls this to hand over an encoded packet to the Android Network Layer.
    *   `AudioErrorCallback`: Rust calls this to report critical C++ errors (e.g., Headset unplugged).

### Threading Model
*   **Rust Side:** Runs on dedicated Real-time Audio Threads managed by Android (Oboe).
*   **Kotlin Side:**
    *   Audio Data arrives via callback on a background thread.
    *   **CRITICAL:** We must dispatch this data to the `BleDriver` immediately without blocking the Audio Thread. We use `channel.trySend()` or `dispatch(Action)` to bridge to the Coroutine world.

---

## 3. Testing Strategy

We separate testing into **Pure Logic** checks and **Integration** checks.

### A. Unit Tests (The Pure Core)
Target: `MeshController`
*   **Philosophy:** Since the controller is an Actor with no Android dependencies, we test it on the JVM.
*   **Mechanism:**
    1.  Instantiate `MeshController`.
    2.  Send `Action.PeerConnected`.
    3.  Send `Action.PacketReceived(Heartbeat)`.
    4.  **Assert** `state.value.network` has updated correctly.
*   **Coverage:** 100% of Topology, State Machine, and Routing logic.

### B. Integration Tests (The Drivers)
Target: `BleDriver`, `VoiceManager`
*   **Philosophy:** These components are hard to unit test because they rely on Hardware.
*   **Mechanism:** We rely on **Manual BDD Scenarios** (see `BEHAVIOR_SPECS.md`) and logging.
*   **Verification:**
    *   Use `Logcat` to verify the "Actor Loop" is processing actions.
    *   Check for "GATT Busy" or "Buffer Overflow" warnings.

### C. Fuzz Testing (Future)
*   We can fuzz the `Packet.fromBytes()` parser by feeding it random byte arrays to ensure it never crashes the app.