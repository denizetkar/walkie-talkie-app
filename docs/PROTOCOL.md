# 📡 Protocol Specification v2.0

This document defines the binary wire format used by the Walkie-Talkie Mesh.
All integers are **Little-Endian**.

---

## 1. Advertising (Service Data)
Broadcasted via BLE Legacy Advertising to facilitate discovery and routing logic.
**UUID:** `3d8a635b-07b0-4892-bf5f-e1f47eaf0291`
**Size:** 10 Bytes

| Offset | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| `0-3` | **Node ID** | `UInt32` | The random ID of the transmitting device. |
| `4-7` | **Network ID** | `UInt32` | The ID of the "Root" this device is following. |
| `8` | **Hops** | `UInt8` | Distance to the Root (0 if I am Root). |
| `9` | **Flags** | `UInt8` | Bitmask. `0x01` = Available for new connections. |

---

## 2. Control Characteristic (Reliable)
Used for Security Handshakes, Topology Updates (Heartbeats), and Routing.
**UUID:** `00002222-0000-1000-8000-00805f9b34fb`
**Properties:** `Write Request` (ACK), `Indication` (ACK).

### Header Format
Every Control packet starts with a 2-byte header.

| Offset | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| `0` | **Ver/Flags** | `UInt8` | `0x10` (Version 1). High nibble = Ver, Low nibble = Flags. |
| `1` | **OpCode** | `UInt8` | Determines the payload type (see below). |

### OpCodes & Payloads

#### `0x01` HELLO
*   **Direction:** Client -> Server
*   **Payload:** Empty `[]`
*   **Description:** Initiates the handshake.

#### `0x02` AUTH_CHALLENGE
*   **Direction:** Server -> Client
*   **Payload:** `[Nonce (8 bytes string)]`
*   **Description:** Server provides a random nonce.

#### `0x03` AUTH_RESPONSE
*   **Direction:** Client -> Server
*   **Payload:** `[Hash (12 bytes)] [ClientNodeID (4 bytes)]`
*   **Description:**
    *   `Hash = SHA256(AccessCode + Nonce + ClientNodeID).truncate(12)`
    *   Server verifies this hash to authenticate the client.

#### `0x04` AUTH_RESULT
*   **Direction:** Server -> Client
*   **Payload:** `[Status (1 byte)]`
*   **Description:** `0x01` = Success, `0x00` = Fail.

#### `0x10` HEARTBEAT
*   **Direction:** Bi-directional (Flooded)
*   **Payload:**
    *   `[0-3] NetID` (`UInt32`) - The Root ID.
    *   `[4-7] Seq` (`UInt32`) - Sequence number (increments on every heartbeat).
    *   `[8] Hops` (`UInt8`) - Hops from Root (incremented on relay).

---

## 3. Audio Characteristic (Unreliable)
Used for Voice Data. Optimized for throughput.
**UUID:** `00001111-0000-1000-8000-00805f9b34fb`
**Properties:** `Write Command` (No ACK), `Notification` (No ACK).

### Packet Layout
There is **NO** header on audio packets to save bandwidth.

| Offset | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| `0-3` | **Origin ID** | `UInt32` | The Node ID of the *original speaker*. |
| `4-5` | **Seq Num** | `UInt16` | Monotonic counter for Jitter Buffer ordering. |
| `6-N` | **Opus Data** | `Bytes` | Variable length Encoded Voice (typ. 20-100 bytes). |

---

## 4. Security Flow (The Bouncer)
1.  **Connect:** Low-level BLE connection established.
2.  **Hello:** Client sends `0x01`.
3.  **Challenge:** Server generates random string, sends `0x02`.
4.  **Calc:** Client computes SHA-256 hash using the shared Access Code.
5.  **Response:** Client sends `0x03` containing the Hash and its own Node ID.
6.  **Verify:** Server computes expected hash.
    *   *Match:* Server sends `0x04 (Success)`. Connection is promoted to "Peer".
    *   *Mismatch:* Server sends `0x04 (Fail)` and disconnects.