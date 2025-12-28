# 🏗️ Architecture & Protocol Design

This document details the internal logic, network topology, and security protocols of the Bluetooth LE Mesh Walkie-Talkie.

---

## 1. Network Topology: Symmetric Mesh

Unlike traditional "Host/Client" models, this app uses a **Symmetric Peer-to-Peer Mesh**.
*   **No Central Authority:** Every node is equal. There is no "Host".
*   **Self-Healing:** If a node leaves, the mesh re-routes automatically.
*   **Island Merging:** Distinct groups of nodes (Islands) automatically detect and merge with each other.

### The "Target vs. Max" Strategy
To balance battery life with connectivity, each node follows this connection logic:

| Parameter | Value | Description |
| :--- | :--- | :--- |
| **Target Peers** | **3** | The "Stable" state. If a node has 3 neighbors, it stops aggressive scanning. |
| **Max Peers** | **5** | The "Burst" capacity. Nodes keep 2 slots open specifically to accept connections from strangers (merging islands). |

### Scanning Behavior
The `MeshNetworkManager` acts as a state machine driven by the number of connected peers:
1.  **Lonely (0-2 Peers):** **Aggressive Scan.** Scans for 5s, pauses for 2s. Tries to fill slots quickly.
2.  **Stable (3-4 Peers):** **Lazy Scan.** Scans once every 30s. This "Heartbeat" looks for other islands to merge with.
3.  **Full (5 Peers):** **Stop Scan.** Saves battery. Only advertises.

---

## 2. Discovery Protocol (Split-Packet Advertising)

Due to the 31-byte limit of Bluetooth LE Legacy Advertising, we split the discovery data into two packets. This ensures we can transmit the Group Name without sacrificing protocol logic.

### Packet A: The Logic (Advertising Packet)
Broadcast constantly. Used by the app to filter relevant nodes.
*   **Service UUID:** `APP_SERVICE_UUID` (128-bit)
*   **Service Data:**
    *   `[0-3]` **Node ID:** 4 bytes (Random Integer). Identifies the node in the mesh.
    *   `[4]` **Availability Flag:** 1 byte. (`1` = Has free slots, `0` = Full).

### Packet B: The Human Info (Scan Response)
Sent only when a scanner requests more info.
*   **Manufacturer Data (ID: 0xFFFF):**
    *   `[0-N]` **Group Name:** UTF-8 String (Max ~27 bytes).

---

## 3. Security: Challenge-Response Handshake

The **Access Code** is treated as a Pre-Shared Key (PSK). It is **never** sent over the air. We use a Zero-Knowledge Proof to verify membership.

### The Handshake Flow
When Node A (Client) connects to Node B (Server):

1.  **Connection:** GATT connection established. State is `UNAUTHENTICATED`.
2.  **Subscription:** Node A subscribes to the `CHALLENGE` characteristic.
3.  **Challenge:** Node B generates a random **Nonce** (8 bytes) and sends it to A.
4.  **Calculation:** Node A computes `SHA256(AccessCode + Nonce)`.
5.  **Response:** Node A writes the following to the `RESPONSE` characteristic:
    *   `[0-15]` **Hash:** First 16 bytes of the SHA256 result.
    *   `[16-19]` **Node ID:** Node A's 4-byte ID.
6.  **Verification:** Node B computes the hash locally.
    *   *Match:* Node A is marked `AUTHENTICATED`. Node ID is mapped to the connection.
    *   *Mismatch:* Node B disconnects immediately.

---

## 4. Software Layering

The app follows a Clean Architecture approach to decouple the hardware from the logic.

### Layer 1: The Brain (`logic/`)
*   **`MeshNetworkManager`:** The central orchestrator.
    *   Maintains the `connectedPeers` list.
    *   Executes the "Target vs. Max" state machine.
    *   Handles "Flooding" (deduplicating and forwarding packets).
    *   **Reactive State:** Exposes `peerCount`, `isScanning`, and `discoveredGroups` as `StateFlows`.

### Layer 2: The Abstraction (`network/`)
*   **`NetworkTransport` (Interface):** Defines the contract for the mesh.
    *   `startDiscovery()`, `connect(address)`, `broadcast(data)`.
    *   Allows future swapping of BLE for WiFi Direct or LoRa without changing app logic.

### Layer 3: The Hardware (`bluetooth/`)
*   **`BleTransport`:** Implements `NetworkTransport` using Android BLE APIs.
    *   **`GattServerHandler`:** Manages incoming connections and the Server-side handshake.
    *   **`GattClientHandler`:** Manages outgoing connections and the Client-side handshake.
    *   **Resource Management:** Tracks coroutine jobs and ensures clean disconnects.

---

## 5. Data Flow (Flooding)

1.  **Source:** User presses PTT. Audio is encoded (Opus).
2.  **Transport:** `MeshNetworkManager` broadcasts packet to all direct neighbors.
3.  **Relay:** Neighbor receives packet.
    *   **Check Cache:** Has this packet hash been seen in the last 5s?
    *   **If Yes:** Drop (Duplicate).
    *   **If No:** Play audio -> Add hash to cache -> Forward to all *other* neighbors.