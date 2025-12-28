# 📻 Bluetooth LE Mesh Walkie-Talkie

**An offline, peer-to-peer voice communication app for Android, built with Kotlin and Rust.**

This project demonstrates a decentralized mobile mesh network using Bluetooth 5 (BLE). It allows a group of users to communicate via voice in real-time without Internet, Wi-Fi, or cellular data. It is designed for hiking, festivals, or emergency situations where infrastructure is unavailable.

---

## 🚀 Key Features

*   **100% Offline:** Works entirely over Bluetooth Low Energy.
*   **Instant On:** No complex setup. Create a group and start talking immediately.
*   **Code-Based Access:** Join an active group anytime using a secure **Access Code**.
*   **Ad-Hoc Mesh:** Messages hop between devices to extend range (Flooding strategy).
*   **Efficient Audio:** Opus codec compression with Jitter Buffering and Packet Loss Concealment.

---

## 📱 App Behavior & Workflow

### 1. Start a Radio Group
*   Go to the **Create** tab and create a group (e.g., "Hiking").
*   The app immediately switches to **Radio Mode**.
*   A random **Access Code** (e.g., `4829`) is displayed on the screen.
*   **Under the hood:** The device starts advertising the Group Name via BLE.

### 2. Tune In (Joiner)
*   Go to the **Join** tab.
*   Scan for nearby groups.
*   Tap on "Hiking".
*   Enter the **Access Code** (`4829`) shared by the host.
*   The app switches to **Radio Mode**.
*   **Under the hood:** The device connects to the mesh and authenticates using the code.

### 3. Talk (Radio Mode)
*   Everyone in the group sees the **Push-to-Talk** button.
*   Audio is compressed (Opus), encrypted with the Access Code, and flooded across the mesh network.
*   Anyone can leave or join at any time.

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

Contributions are welcome! Please read the `CONTRIBUTING.md` (Coming Soon) for details on our code of conduct and the process for submitting pull requests.

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