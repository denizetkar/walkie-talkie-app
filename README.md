# 📻 Bluetooth LE Mesh Walkie-Talkie

**An offline, peer-to-peer voice communication app for Android, built with Kotlin and Rust.**

This project demonstrates a decentralized mobile mesh network using Bluetooth 5 (BLE). It allows a group of users to communicate via voice in real-time without Internet, Wi-Fi, or cellular data. It is designed for hiking, festivals, or emergency situations where infrastructure is unavailable.

---

## 🚀 Key Features

*   **100% Offline:** Works entirely over Bluetooth Low Energy.
*   **Ad-Hoc Mesh:** Messages hop between devices to extend range (Flooding strategy).
*   **Cross-Layer Architecture:**
    *   **UI:** Modern Android (Jetpack Compose).
    *   **Engine:** High-performance Systems programming (Rust).
*   **Efficient Audio:** Opus codec compression with Jitter Buffering and Packet Loss Concealment.

---

## 📱 App Behavior & Workflow

The application operates in two distinct phases: **Setup (Lobby)** and **Active (Mesh)**.

### Phase 1: The Setup (Lobby)
Before the group spreads out, devices must perform a secure handshake to exchange a **Group Key** and **User Map**.

#### 1. Creating a Group (The Host)
*   User A selects **"Create Group"** and enters a name (e.g., "Hiking Squad").
*   **Behavior:**
    *   The device becomes a **GATT Server**.
    *   It starts **Advertising** a specific Service UUID containing the Group Name.
    *   It listens for incoming connection requests.

#### 2. Joining a Group (The Peers)
*   User B selects **"Join Group"**.
*   **Behavior:**
    *   The device scans for advertisers with the App's Service UUID.
    *   User B sees "Hiking Squad" in the list and taps it.
    *   The device initiates a **GATT Connection** to User A (Host).
    *   User B sends their "Display Name" to the Host.

#### 3. The Handshake
*   The Host receives the request and approves User B.
*   **Data Exchange:** The Host sends a **Shared Secret (Group Key)** and a unique **Member ID** back to User B.
*   *Note:* This key is used to sign/encrypt voice packets later, ensuring only group members can listen.

---

### Phase 2: Active Mode (The Mesh)
Once the group is formed, everyone transitions to the "Run" screen. The distinction between Host and Peer disappears; all nodes become equal relays.

#### 1. The Network Topology
*   **Hybrid Role:** Every device acts as both an Advertiser and a Scanner simultaneously (time-sliced).
*   **Connection:** Devices automatically form GATT connections with the strongest nearby signals that possess the valid Group Key.
*   **Relay:** If User A is connected to B, and B is connected to C, audio from A is forwarded by B to reach C.

#### 2. Voice Transmission (Push-to-Talk)
*   **Action:** User holds the PTT button.
*   **Audio Pipeline:**
    1.  **Capture:** Microphone records PCM audio (via Oboe/Rust).
    2.  **Compress:** Audio is encoded using **Opus** (8kbps - 16kbps).
    3.  **Packetize:** Data is wrapped in a custom packet: `[SenderID | SeqNum | OpusData]`.
    4.  **Broadcast:** The packet is sent to all currently connected neighbors via BLE Write Command (Write without Response).

#### 3. Voice Reception & Flooding
*   **Flooding Logic:** When a device receives a packet:
    1.  **Deduplication:** Checks the `[SenderID + SeqNum]` against a local cache. If seen before, the packet is dropped.
    2.  **Playback:** If new, the packet is pushed to the **Jitter Buffer** for that SenderID.
    3.  **Relay:** The packet is immediately re-transmitted to all *other* connected neighbors (TTL decremented).
*   **Rendering:** The Rust audio engine mixes streams from multiple users, applies Packet Loss Concealment (PLC) for missing frames, and plays the result.

---

## 🛠 Architecture

This project uses a hybrid approach to leverage the best tools for each job:

| Component | Technology | Responsibility |
| :--- | :--- | :--- |
| **UI / Presentation** | **Kotlin** (Jetpack Compose) | Rendering views, Navigation, Permissions, State Management. |
| **Bluetooth Layer** | **Kotlin** (Android APIs) | Scanning, Advertising, GATT connections (managed via Android SDK for stability). |
| **Bridge** | **JNI / UniFFI** | Passing byte arrays between the Android runtime and the Rust engine. |
| **Core Engine** | **Rust** | Audio I/O (Oboe), Opus Encoding/Decoding, Jitter Buffer, Packet Logic. |

### Directory Structure
```text
├── app/src/main/java/   # Kotlin UI and Bluetooth Logic
├── rust/                # Rust Library (The "Brain")
│   ├── src/lib.rs       # JNI Entry points
│   ├── Cargo.toml       # Rust dependencies (opus, oboe, bytes)
└── gradle/              # Build configuration
```

---

## 📦 Getting Started

### Prerequisites
1.  **Android Studio** (Koala or later).
2.  **Rust Toolchain** (`rustup`).
3.  **Cargo NDK**: `cargo install cargo-ndk`.
4.  **Python** (Required for UniFFI binding generation).

### Building
1.  Clone the repo.
2.  Open in Android Studio.
3.  Sync Gradle (this will trigger the Rust build scripts).
4.  Connect two Android devices (Android 9.0+ recommended for BLE 5 features).
5.  Run the app.

---

## 🤝 Contributing

Contributions are welcome! Please read the [CONTRIBUTING.md](CONTRIBUTING.md) (Coming Soon) for details on our code of conduct and the process for submitting pull requests.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

---

## 📄 License

Distributed under the MIT License. See [license](./LICENSE) for more information.

---

## ⚠️ Disclaimer

This app is currently in **Alpha**.
*   Bluetooth Mesh performance varies heavily based on hardware.
*   Audio latency is expected to be between 200ms - 500ms depending on the number of hops.
*   No encryption is currently implemented (Voice is sent in cleartext, signed by Group Key).