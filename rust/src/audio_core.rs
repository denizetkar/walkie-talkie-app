#![allow(dead_code)] // Logic is consumed only by Android target

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

    // Helper: Generates a dummy Opus packet (3 bytes) that won't crash the decoder
    // Byte sequence: 0xF8 (Config 0, Mono), 0xFF, 0xFE (Payload)
    fn get_dummy_opus_frame() -> Vec<u8> {
        vec![0xF8, 0xFF, 0xFE]
    }

    #[test]
    fn test_protocol_wrapping() {
        // SCENARIO: Verify binary serialization round-trip.
        // Ensures Little-Endian format is used consistently for NodeID and SeqNum.
        let origin = 0xDEADBEEF;
        let seq = 42;
        let payload = vec![0xAA, 0xBB, 0xCC];

        let wrapped = wrap_packet(origin, seq, &payload);
        let (out_id, out_seq, out_data) = unwrap_packet(&wrapped).unwrap();

        assert_eq!(origin, out_id, "Origin ID mismatch");
        assert_eq!(seq, out_seq, "Sequence number mismatch");
        assert_eq!(payload, out_data, "Payload corruption");
    }

    #[test]
    fn test_sequence_wrapping_overflow() {
        // SCENARIO: Sequence numbers are u16. They wrap from 65535 -> 0.
        // BTreeMap sorts 0 before 65535, so we must ensure the engine
        // establishes the correct "Next Expected" state BEFORE the wrap occurs.
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let data = get_dummy_opus_frame();

        // 1. Establish state: Push packets leading up to the limit (Threshold=6)
        // Push 65530..65535.
        for i in 65530..=65535 {
            stream.push_packet(i, data.clone());
        }

        // 2. Process first frame to Exit Buffering
        // Smallest key is 65530. Engine picks it.
        // State becomes: Buffering=False, NextExpected=65531.
        let _ = stream.process_next_frame();
        assert!(!stream.buffering);
        assert_eq!(stream.next_expected_seq, Some(65531));

        // 3. Now introduce the wrap packets (0, 1, 2)
        stream.push_packet(0, data.clone());
        stream.push_packet(1, data.clone());
        stream.push_packet(2, data.clone());

        // 4. Drain the high numbers (65531..65535)
        for _ in 65531..=65535 {
            stream.process_next_frame();
        }

        // 5. The Moment of Truth: Wrap 65535 -> 0
        // Previous step consumed 65535. Logic: 65535.wrapping_add(1) == 0.
        assert_eq!(stream.next_expected_seq, Some(0));

        // 6. Verify it finds packet 0 (which exists in the map)
        stream.process_next_frame();
        assert_eq!(stream.next_expected_seq, Some(1));
    }

    #[test]
    fn test_buffer_overflow_protection() {
        // SCENARIO: Network burst / Latency spike.
        // If we receive too many packets (more than max_jitter_packets),
        // we must drop the OLDEST ones to catch up to real-time.

        // Setup: Max 10 packets allowed (600ms buffer / 60ms frame)
        let mut stream = RemoteStream::new(48000, 600, 60);
        let data = get_dummy_opus_frame();

        // 1. Flood with 20 packets (Seq 100 to 119)
        for i in 100..120 {
            stream.push_packet(i, data.clone());
        }

        // 2. Initial State: Buffer holds 20 items (Overflowed)
        // Note: The cleanup happens lazily inside `process_next_frame`.
        assert_eq!(stream.jitter_buffer.len(), 20);

        // 3. Trigger Processing
        // Logic detects overflow -> Removes oldest 10 packets (100..109) -> Jumps to 110.
        stream.process_next_frame();

        // 4. Verify Cleanup
        assert!(stream.jitter_buffer.len() <= 10, "Buffer should be trimmed to max size");

        // 5. Verify Jump
        // We skipped 100..109. We processed 110. Next expected is 111.
        assert_eq!(stream.next_expected_seq, Some(111));
    }

    #[test]
    fn test_jitter_reordering() {
        // SCENARIO: UDP/BLE packets arrive out of order.
        // The Jitter Buffer (BTreeMap) should sort them automatically.
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let data = get_dummy_opus_frame();

        // 1. Push mixed sequence: 3, 1, 2
        stream.push_packet(3, data.clone());
        stream.push_packet(1, data.clone());
        stream.push_packet(2, data.clone());

        // 2. Fill to threshold (Need 3 more)
        stream.push_packet(4, data.clone());
        stream.push_packet(5, data.clone());
        stream.push_packet(6, data.clone());

        // 3. Process
        let _ = stream.process_next_frame();

        // 4. Verify Sorting
        assert!(!stream.buffering);
        // Logic should pick '1' (lowest key), not '3' (first inserted)
        // So next expected is 2.
        assert_eq!(stream.next_expected_seq, Some(2));
    }

    #[test]
    fn test_packet_loss_concealment_trigger() {
        // SCENARIO: Packet 5 is lost.
        // Logic should detect the gap (we have 6, but expected 5).
        // It should tell Opus to generate PLC (fake audio) and move on.
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let data = get_dummy_opus_frame();

        // 1. Fill buffer: 0, 1, 2, 3, 4, (SKIP 5), 6
        for i in 0..5 { stream.push_packet(i, data.clone()); }
        stream.push_packet(6, data.clone());

        // 2. Drain valid packets 0..4
        for _ in 0..5 { stream.process_next_frame(); }
        assert_eq!(stream.next_expected_seq, Some(5));

        // 3. Process missing packet 5
        // We have 6 in buffer (Lookahead window). So we know 5 is truly missing.
        let _ = stream.process_next_frame();

        // 4. Verify Recovery
        // Even though we didn't have data for 5, we should have advanced past it.
        assert_eq!(stream.next_expected_seq, Some(6));
    }

    #[test]
    fn test_underrun_recovery() {
        // SCENARIO: Internet cuts out. Buffer drains completely.
        // Engine should enter "Buffering" state and wait for threshold before playing again.
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let data = get_dummy_opus_frame();

        // 1. Fill to threshold (6 packets) and drain them all immediately
        for i in 0..6 { stream.push_packet(i, data.clone()); }
        for _ in 0..6 { stream.process_next_frame(); }

        // 2. Buffer is empty. Next call implies underrun.
        stream.process_next_frame();
        assert!(stream.buffering, "Should enter buffering state when empty");

        // 3. Add single packet (Seq 6).
        // Should NOT play yet because threshold (6) is not met.
        stream.push_packet(6, data.clone());
        let played = stream.process_next_frame();
        assert!(!played, "Should not play single packet while buffering");
        assert!(stream.buffering);

        // 4. Fill remaining required packets (Seq 7..11)
        for i in 7..12 { stream.push_packet(i, data.clone()); }

        // 5. Verify Resume
        let played_now = stream.process_next_frame();
        assert!(played_now, "Should resume playing");
        assert!(!stream.buffering);
        assert_eq!(stream.next_expected_seq, Some(7)); // Consumed 6
    }

    #[test]
    fn test_packet_parsing_failures() {
        assert!(unwrap_packet(&[]).is_none());
        assert!(unwrap_packet(&[0, 1, 2]).is_none());
        // 5 bytes < HEADER_SIZE (6)
        assert!(unwrap_packet(&[0; 5]).is_none());
    }

    #[test]
    fn test_huge_gap_resync() {
        let mut stream = RemoteStream::new(48000, 1000, 60);
        let data = get_dummy_opus_frame();

        // 1. Manually bypass buffering to test gap logic in isolation
        stream.buffering = false;

        // 2. Initialize Stream State
        stream.push_packet(100, data.clone());
        // First call: sets next_expected_seq = 100 (fallback logic), returns false
        let _ = stream.process_next_frame();
        assert_eq!(stream.next_expected_seq, Some(100));

        // Second call: plays 100, sets next = 101
        let _ = stream.process_next_frame();
        assert_eq!(stream.next_expected_seq, Some(101));

        // 3. Push 5000 (Huge gap)
        stream.push_packet(5000, data.clone());

        // 4. Process
        // 5000 is far beyond 101 + Window. Should jump.
        let played = stream.process_next_frame();

        assert!(played, "Should play the new packet immediately (Resync)");
        assert_eq!(stream.next_expected_seq, Some(5001));
    }
}