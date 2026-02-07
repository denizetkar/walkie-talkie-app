use std::collections::BTreeMap;
use byteorder::{ByteOrder, LittleEndian};
use opus_codec::{Decoder, Channels, SampleRate};

// ===========================================================================
// CONSTANTS & TUNING
// ===========================================================================

// 120ms buffer at 48kHz (48000 * 0.120).
// We need space to store decoded PCM data before mixing it.
pub const PCM_BUFFER_SIZE: usize = 5760;

// Protocol Overhead: [OriginID(4)] + [Seq(2)] = 6 bytes
pub const PACKET_HEADER_SIZE: usize = 6;

// Jitter Buffer: Start playing only after we have buffered this many packets.
// 6 packets * 60ms = 360ms of initial delay.
// This allows the network to be unstable without audio cutting out immediately.
pub const JITTER_BUFFER_START_THRESHOLD: usize = 6;

// Packet Loss: How far ahead do we look?
// If we expect packet 100, but have 105, we assume 101-104 are lost.
// If we have 200, we assume 100 is just REALLY late and ignore 200 for now.
pub const JITTER_LOOKAHEAD_WINDOW: u16 = 10;

#[derive(Clone, Copy, uniffi::Record)]
pub struct AudioConfig {
    pub sample_rate: i32,
    pub frame_size_ms: i32,
    pub jitter_buffer_ms: i32,
    pub input_device_id: i32,
    pub output_device_id: i32,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48000,
            frame_size_ms: 60,
            jitter_buffer_ms: 1000,
            input_device_id: 0,
            output_device_id: 0,
        }
    }
}

// ===========================================================================
// PROTOCOL HELPERS
// ===========================================================================

pub fn wrap_packet(origin_id: u32, seq: u16, opus_data: &[u8]) -> Vec<u8> {
    // Pre-allocate exact size to avoid reallocation on the audio thread
    let mut packet = Vec::with_capacity(PACKET_HEADER_SIZE + opus_data.len());
    let mut id_buf = [0u8; 4];
    let mut seq_buf = [0u8; 2];

    // Write Little-Endian integers (Standard for network protocols)
    LittleEndian::write_u32(&mut id_buf, origin_id);
    LittleEndian::write_u16(&mut seq_buf, seq);

    packet.extend_from_slice(&id_buf);
    packet.extend_from_slice(&seq_buf);
    packet.extend_from_slice(opus_data);
    packet
}

pub fn unwrap_packet(data: &[u8]) -> Option<(u32, u16, &[u8])> {
    if data.len() < PACKET_HEADER_SIZE { return None; }

    let origin_id = LittleEndian::read_u32(&data[0..4]);
    let seq = LittleEndian::read_u16(&data[4..6]);
    // Return slice reference to avoid copying the payload
    Some((origin_id, seq, &data[PACKET_HEADER_SIZE..]))
}

// ===========================================================================
// JITTER BUFFER & DECODER LOGIC
// ===========================================================================

pub struct RemoteStream {
    decoder: Decoder,
    // BTreeMap keeps packets SORTED by sequence number automatically.
    // Key: Sequence Number (u16), Value: Opus Encoded Bytes
    jitter_buffer: BTreeMap<u16, Vec<u8>>,

    // The sequence number we expect to play next.
    next_expected_seq: Option<u16>,

    // State flag: Are we waiting to build up a buffer?
    pub buffering: bool,

    // Scratch buffer for raw PCM audio (One frame's worth)
    pub pcm_buffer: [i16; PCM_BUFFER_SIZE],

    // How much valid audio is currently in pcm_buffer
    pub valid_samples: usize,

    // Tracks how long this peer has been silent (for cleanup)
    pub silence_counter: usize,

    // Configuration: Max packets to hold before dropping (to reduce latency)
    max_jitter_packets: usize,
}

impl RemoteStream {
    pub fn new(sample_rate_hz: i32, jitter_buffer_ms: i32, frame_size_ms: i32) -> Self {
        let rate = match sample_rate_hz {
            8000 => SampleRate::Hz8000,
            12000 => SampleRate::Hz12000,
            16000 => SampleRate::Hz16000,
            24000 => SampleRate::Hz24000,
            48000 => SampleRate::Hz48000,
            _ => SampleRate::Hz48000, // Default to high quality
        };

        let decoder = Decoder::new(rate, Channels::Mono).unwrap();
        let max_packets = (jitter_buffer_ms / frame_size_ms) as usize;

        Self {
            decoder,
            jitter_buffer: BTreeMap::new(),
            next_expected_seq: None,
            buffering: true,
            pcm_buffer: [0i16; PCM_BUFFER_SIZE],
            valid_samples: 0,
            silence_counter: 0,
            max_jitter_packets: max_packets,
        }
    }

    /// Adds a packet to the buffer.
    pub fn push_packet(&mut self, seq: u16, data: Vec<u8>) {
        // If we get a packet, reset silence counter so we don't delete this peer.
        self.silence_counter = 0;
        self.jitter_buffer.insert(seq, data);
    }

    /// The Core "Game Loop" for Audio.
    /// Decides what to decode next: Real Data, Silence (Buffering), or PLC (Loss).
    /// Returns `true` if `pcm_buffer` was filled with new audio.
    pub fn process_next_frame(&mut self) -> bool {
        // 1. SAFETY: Prevent memory explosion / High Latency
        // If we have too many packets (latency too high), drop the oldest ones.
        while self.jitter_buffer.len() > self.max_jitter_packets {
            if let Some(&oldest_seq) = self.jitter_buffer.keys().next() {
                self.jitter_buffer.remove(&oldest_seq);
                // We force our expectation to catch up to the stream
                self.next_expected_seq = Some(oldest_seq.wrapping_add(1));
            }
        }

        // 2. STATE: Buffering
        // If we ran out of data recently, we wait until we have enough to start smoothly.
        if self.buffering {
            if self.jitter_buffer.len() >= JITTER_BUFFER_START_THRESHOLD {
                // Threshold met, start playing!
                self.buffering = false;
                // If we don't have an expected sequence yet, start with the first one we have.
                if self.next_expected_seq.is_none() {
                     if let Some(&first) = self.jitter_buffer.keys().next() {
                        self.next_expected_seq = Some(first);
                    }
                }
            } else {
                return false; // Output nothing (Silence) while buffering
            }
        }

        // 3. DECISION: What packet do we decode?
        // Option<Vec<u8>>: Some(data) = Normal, None = Packet Loss (PLC)
        let mut packet_to_decode: Option<Option<Vec<u8>>> = None;

        if let Some(expected) = self.next_expected_seq {
            if let Some(data) = self.jitter_buffer.remove(&expected) {
                // CASE A: Perfect! We have the exact packet we wanted.
                self.next_expected_seq = Some(expected.wrapping_add(1));
                packet_to_decode = Some(Some(data));
            } else {
                // CASE B: Missing Packet.
                // Check if we have "future" packets.
                let has_future_packets = self.jitter_buffer.keys().any(|&k| {
                    // wrapping_sub handles the u16 overflow (e.g. 0 - 65535 = 1)
                    let delta = k.wrapping_sub(expected);
                    delta > 0 && delta < JITTER_LOOKAHEAD_WINDOW
                });

                if has_future_packets {
                    // We have future data, so the expected packet is truly LOST.
                    // Tell Opus to generate fake audio (PLC) for this gap.
                    self.next_expected_seq = Some(expected.wrapping_add(1));
                    packet_to_decode = Some(None); // None means "PLC please"
                } else if self.jitter_buffer.is_empty() {
                    // Buffer is empty. We ran dry.
                    // Go back to buffering state.
                    self.buffering = true;
                    return false;
                } else {
                    // Huge Gap (e.g., resumed after 10 seconds).
                    // Give up on the old stream and jump to the new data.
                    if let Some(&next_avail) = self.jitter_buffer.keys().next() {
                        self.next_expected_seq = Some(next_avail.wrapping_add(1));
                        packet_to_decode = Some(Some(self.jitter_buffer.remove(&next_avail).unwrap()));
                    }
                }
            }
        } else {
            // Edge Case: Should be handled by buffering logic, but safety fallback.
             if let Some(&first) = self.jitter_buffer.keys().next() {
                self.next_expected_seq = Some(first);
             }
             return false;
        }

        // 4. ACTION: Decode
        if let Some(maybe_data) = packet_to_decode {
            // If maybe_data is None, we pass &[] and fec=true to trigger PLC.
            let (input, fec) = match maybe_data {
                Some(ref data) => (data.as_slice(), false),
                None => (&[] as &[u8], true),
            };

            // Validating decode result
            match self.decoder.decode(input, &mut self.pcm_buffer, fec) {
                Ok(len) if len > 0 => {
                    self.valid_samples = len;
                    return true;
                }
                Ok(_) => return false, // 0 samples decoded
                Err(e) => {
                    // This happens if data is corrupt. We just output silence.
                    log::warn!("Opus decode error: {}", e);
                    self.valid_samples = 0;
                    return false;
                }
            }
        }

        false
    }
}

// ===========================================================================
// UNIT TESTS (HOST SIDE)
// ===========================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_protocol_wrapping() {
        let origin = 0xDEADBEEF;
        let seq = 42;
        let payload = vec![0xAA, 0xBB, 0xCC];

        let wrapped = wrap_packet(origin, seq, &payload);
        let (out_id, out_seq, out_data) = unwrap_packet(&wrapped).unwrap();

        assert_eq!(origin, out_id);
        assert_eq!(seq, out_seq);
        assert_eq!(payload, out_data);
    }

    #[test]
    fn test_jitter_reordering() {
        let mut stream = RemoteStream::new(48000, 1000, 60);

        // Simulate receiving packets out of order: 3, 1, 2
        // We use dummy Opus data (empty vec might fail decode, so we trust logic flow or mock decoder if possible)
        // Note: Real opus decoder needs valid frames or it returns error.
        // For this logic test, we rely on the JitterBuffer state, assuming decode works or fails gracefully.

        let dummy_data = vec![0u8; 10];

        // 1. Push Seq 3 (Future)
        stream.push_packet(3, dummy_data.clone());
        assert!(stream.buffering); // Should be buffering (count 1 < 6)

        // 2. Push packets out of order
        stream.push_packet(1, dummy_data.clone());
        stream.push_packet(2, dummy_data.clone());
        stream.push_packet(4, dummy_data.clone());
        stream.push_packet(5, dummy_data.clone());
        stream.push_packet(6, dummy_data.clone()); // Count = 6. Threshold reached.

        // 3. Process
        // Buffer has: 1, 2, 3, 4, 5, 6.
        // Logic should pick 1 first.

        // Force state check:
        // We can't easily check internal BTreeMap without exposing it,
        // but we can check `process()` outcome order if we had a mock decoder.
        // Instead, we verify the `buffering` flag logic works.

        assert!(stream.buffering);
        // Trigger the start threshold
        let _ = stream.process_next_frame();

        assert!(!stream.buffering);
        assert_eq!(stream.next_expected_seq, Some(2));
    }

    #[test]
    fn test_packet_loss_concealment_trigger() {
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let dummy_data = vec![0u8; 10];

        // Pre-fill to pass buffering threshold
        for i in 0..10 {
            if i != 5 { // SKIP SEQ 5
                stream.push_packet(i, dummy_data.clone());
            }
        }

        // Process up to gap
        for i in 0..5 {
            let _ = stream.process_next_frame();
            assert_eq!(stream.next_expected_seq, Some(i + 1));
        }

        // Now we are at expected=5. Map has 6, 7, 8...
        // process() should see 5 is missing, but 6 is present (within lookahead).
        // It should trigger PLC (return None to decoder) and increment expected to 6.
        let _ = stream.process_next_frame();
        assert_eq!(stream.next_expected_seq, Some(6));
    }
}