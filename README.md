# 📻 Bluetooth LE Mesh Walkie-Talkie

**An offline, peer-to-peer voice communication app for Android, built with Kotlin and Rust.**

This project demonstrates a decentralized mobile mesh network using Bluetooth 5 (BLE). It allows a group of users to communicate via voice in real-time without Internet, Wi-Fi, or cellular data. It is designed for hiking, festivals, or emergency situations where infrastructure is unavailable.

> **See [ARCHITECTURE.md](./ARCHITECTURE.md) for deep technical details on the topology, protocol, and security.**

---

## 🚀 Key Features

*   **Symmetric Mesh:** No "Host" node. Every device is equal. The network uses a Root-Based Convergence strategy to merge groups automatically.
*   **Self-Healing:** Automatically merges separated groups ("Islands") using specific topology logic.
*   **Zero-Knowledge Security:** The Access Code is never sent over the air. Uses a Challenge-Response handshake with SHA-256.
*   **"Hot Mic" Audio:** Audio streams are kept open for instant Push-to-Talk (PTT) response, avoiding hardware warm-up latency.
*   **High Performance:** Powered by **Rust**, **Oboe** (AAudio), and **Opus** for high-performance voice encoding.
*   **100% Offline:** Works entirely over Bluetooth Low Energy.

---

## 📱 App Behavior & Workflow

### 1. Create or Join
*   **Create:** Enter a Group Name (e.g., "Hiking"). The app generates a random Access Code.
*   **Join:** Scan for nearby groups, select "Hiking", and enter the Access Code.
*   **Discovery:** Uses split advertising: Service Data contains mesh topology info; Scan Response contains the human-readable Group Name.

### 2. The Mesh Logic
*   **Auto-Connect:** The app automatically connects to available neighbors to reach a target of **3 peers**.
*   **Island Merging:** Even when stable, the app performs "Lazy Scans" to find and bridge with other clusters of users.
*   **Scan Rate Limiting:** Includes a smart limiter to respect Android's strict "5 scans per 30 seconds" system restriction.

### 3. Talk (Radio Mode)
*   **Push-to-Talk:** Audio is compressed (Opus 48kHz), and flooded across the mesh.
*   **Flooding:** Messages hop from node to node. A deduplication cache prevents echo loops.
*   **Jitter Buffering:** A Rust-based jitter buffer reorders packets and smooths out BLE transmission irregularities.

---

## 🛠 Architecture

This project uses a hybrid approach to leverage the best tools for each job:

| Component | Technology | Responsibility |
| :--- | :--- | :--- |
| **UI / Presentation** | **Kotlin** (Jetpack Compose) | Rendering views, State Management (ViewModel). |
| **Mesh Logic** | **Kotlin** (Coroutines/Flow) | Topology management, State Machine, Packet Flooding. |
| **Transport Layer** | **Kotlin** (Android BLE) | Scanning, Advertising, GATT Server/Client, Operation Queueing. |
| **Audio Engine** | **Rust** (Oboe + Opus) | Low-latency Audio I/O, Encoding/Decoding, Jitter Buffer (BTreeMap). |
| **FFI Bridge** | **UniFFI** | Generates the Kotlin bindings to talk to Rust safely. |

### Directory Structure
```text
├── app/src/main/java/com/denizetkar/walkietalkieapp/
│   ├── logic/           # The Brain (MeshNetworkManager, TopologyEngine)
│   ├── network/         # Transport Interface (BleDriver, TransportModels)
│   ├── bluetooth/       # BLE Implementation (GattServer, GattClient, OperationQueue)
│   ├── engine/          # Generated UniFFI Bindings (Rust Interface)
│   └── MainActivity.kt
├── rust/                # Rust Library (Audio Engine)
│   ├── src/             # Rust Source (lib.rs, Oboe impl)
│   ├── build.ps1        # PowerShell Build Script (NDK Glue)
│   └── build.rs         # Cargo Build Script (Linker Flags)
└── gradle/              # Build configuration
```

---

## 📦 Getting Started

### Prerequisites
1.  **Android Studio** (Ladybug or later).
2.  **Rust Toolchain** (`rustup` with `aarch64-linux-android` target).
3.  **Android NDK** (Version 29+).
4.  **Cargo NDK**: `cargo install cargo-ndk`.
5.  **Python**: Required for `uniffi-bindgen` to generate Kotlin code.

### Building
1.  Clone the repo.
2.  Open in Android Studio.
3.  **Sync Gradle**: This triggers the `buildRust` task, which:
    *   Compiles the Rust code for `arm64-v8a` (Android 30+).
    *   Copies `libc++_shared.so` (required for Oboe) to `jniLibs`.
    *   Generates the Kotlin bindings via UniFFI.
4.  Connect two Android devices (Android 9.0+ recommended).
5.  Run the app.

---

## 🤝 Contributing

Contributions are welcome! Please read the [CONTRIBUTING.md](./CONTRIBUTING.md) (Coming Soon) for details on our code of conduct and the process for submitting pull requests.

---

## 📄 License

Distributed under the MIT License. See [license](./LICENSE) for more information.

---

## ⚠️ Disclaimer

This app is currently in **Alpha**.
*   Bluetooth Mesh performance varies heavily based on hardware.
*   **Latency:** Audio latency is approximately **300ms - 800ms**. This is due to the large packet size (60ms) and the conservative jitter buffer required for unstable BLE connections.
*   **Security:** Voice packets are currently sent unencrypted (though the handshake is secure).