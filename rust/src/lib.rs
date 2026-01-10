use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender as StdSender;
use std::collections::{BTreeMap, HashMap};
use crossbeam_channel::{unbounded, Receiver, Sender};

uniffi::setup_scaffolding!("walkie_talkie_engine");

// ===========================================================================
// CENTRALIZED CONFIGURATION
// ===========================================================================

// --- Buffer & Channel Limits ---
// Buffer size for 120ms of audio at 48kHz (48000Hz * 0.120s = 5760 samples).
// We need enough room to hold data while processing.
const MAX_BUFFER_SIZE: usize = 5760;

// Maximum size of a raw Opus encoded packet. 512 bytes is plenty for voice.
const OPUS_OUT_BUFFER_SIZE: usize = 512;

// --- Protocol Layout ---
// Header: [OriginID (4 bytes)] + [Sequence (2 bytes)]
const PACKET_HEADER_SIZE: usize = 6;

// --- Tuning Parameters ---
// How many frames of silence (missing packets) before we delete a peer?
// 50 frames * 60ms = ~3 seconds.
const PEER_TIMEOUT_FRAMES: usize = 50;

// Jitter Buffer: How many packets to buffer before STARTING playback?
// 6 packets * 60ms = 360ms latency.
// Higher = smoother audio, Lower = faster conversation.
const JITTER_BUFFER_START_THRESHOLD: usize = 6;

// Jitter Buffer: How far ahead to check for a "future" packet if the expected one is missing?
// If we expect Seq 10, but have Seq 15, we treat 11-14 as lost and skip to 15.
const JITTER_LOOKAHEAD_WINDOW: u16 = 10;

// ===========================================================================
// SHARED DEFINITIONS
// ===========================================================================

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum AudioError {
    #[error("Failed to open audio device")]
    DeviceError,
    #[error("Failed to encode audio")]
    EncoderError,
    #[error("Failed to decode audio")]
    DecoderError,
}

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

// --- Callback Interfaces ---

#[uniffi::export(callback_interface)]
pub trait PacketTransport: Send + Sync {
    fn send_packet(&self, data: Vec<u8>);
}

#[uniffi::export(callback_interface)]
pub trait AudioErrorCallback: Send + Sync {
    fn on_engine_error(&self, code: i32);
}

// ===========================================================================
// ANDROID IMPLEMENTATION
// ===========================================================================

#[cfg(target_os = "android")]
mod real_impl {
    use super::*;
    use std::thread;
    use std::sync::mpsc::{channel, Receiver as StdReceiver};
    use byteorder::{ByteOrder, LittleEndian};

    use oboe::{
        AudioInputCallback, AudioOutputCallback, AudioStreamBuilder, AudioStreamAsync,
        PerformanceMode, SharingMode, Mono, DataCallbackResult, InputPreset, Usage,
        Input, Output, AudioInputStreamSafe, AudioOutputStreamSafe, AudioStream
    };
    use opus_codec::{Encoder, Decoder, Application, Channels, SampleRate};

    // --- Helpers ---

    fn map_sample_rate(hz: i32) -> SampleRate {
        match hz {
            8000 => SampleRate::Hz8000,
            12000 => SampleRate::Hz12000,
            16000 => SampleRate::Hz16000,
            24000 => SampleRate::Hz24000,
            48000 => SampleRate::Hz48000,
            _ => {
                log::warn!("Unsupported sample rate: {}. Defaulting to 48kHz.", hz);
                SampleRate::Hz48000
            }
        }
    }

    fn wrap_packet(origin_id: u32, seq: u16, opus_data: &[u8]) -> Vec<u8> {
        let mut packet = Vec::with_capacity(PACKET_HEADER_SIZE + opus_data.len());
        let mut id_buf = [0u8; 4];
        let mut seq_buf = [0u8; 2];
        LittleEndian::write_u32(&mut id_buf, origin_id);
        LittleEndian::write_u16(&mut seq_buf, seq);
        packet.extend_from_slice(&id_buf);
        packet.extend_from_slice(&seq_buf);
        packet.extend_from_slice(opus_data);
        packet
    }

    fn unwrap_packet(data: &[u8]) -> Option<(u32, u16, &[u8])> {
        if data.len() < PACKET_HEADER_SIZE { return None; }
        let origin_id = LittleEndian::read_u32(&data[0..4]);
        let seq = LittleEndian::read_u16(&data[4..6]);
        Some((origin_id, seq, &data[PACKET_HEADER_SIZE..]))
    }

    // --- Core Logic ---

    struct PeerStream {
        decoder: Decoder,
        jitter_buffer: BTreeMap<u16, Vec<u8>>,
        next_expected_seq: Option<u16>,
        buffering: bool,
        buffer: [i16; MAX_BUFFER_SIZE], // Internal scratch buffer for decoding
        buffer_len: usize,              // How much valid data is in buffer
        silence_counter: usize,         // For garbage collection
    }

    impl PeerStream {
        fn new(sample_rate_hz: i32) -> Self {
            let rate = map_sample_rate(sample_rate_hz);
            let decoder = Decoder::new(rate, Channels::Mono).unwrap();
            Self {
                decoder,
                jitter_buffer: BTreeMap::new(),
                next_expected_seq: None,
                buffering: true,
                buffer: [0i16; MAX_BUFFER_SIZE],
                buffer_len: 0,
                silence_counter: 0,
            }
        }
    }

    #[derive(uniffi::Object)]
    pub struct AudioEngine {
        input_stream: Mutex<Option<AudioStreamAsync<Input, InputCallback>>>,
        output_stream: Mutex<Option<AudioStreamAsync<Output, OutputCallback>>>,
        tx_transport: StdSender<Vec<u8>>,
        packet_tx: Mutex<Option<Sender<(u32, u16, Vec<u8>)>>>,
        sequence_number: Arc<Mutex<u16>>,
        config: AudioConfig,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
    }

    // --- RESOURCE CLEANUP ---
    impl Drop for AudioEngine {
        fn drop(&mut self) {
            // Automatically cleanup when the object is destroyed
            self.release_resources();
        }
    }

    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(
            config: AudioConfig,
            transport: Box<dyn PacketTransport>,
            callback: Box<dyn AudioErrorCallback>,
            own_node_id: u32
        ) -> Self {
            let (tx, rx): (StdSender<Vec<u8>>, StdReceiver<Vec<u8>>) = channel();

            thread::spawn(move || {
                while let Ok(packet) = rx.recv() {
                    transport.send_packet(packet);
                }
            });

            Self {
                input_stream: Mutex::new(None),
                output_stream: Mutex::new(None),
                tx_transport: tx,
                packet_tx: Mutex::new(None),
                sequence_number: Arc::new(Mutex::new(0)),
                config,
                is_mic_enabled: Arc::new(AtomicBool::new(false)),
                own_node_id,
                error_callback: Arc::new(callback),
            }
        }

        /// Starts BOTH Input and Output streams.
        /// Call this when joining a group.
        pub fn start_session(&self) -> Result<(), AudioError> {
            log::info!("Starting Audio Session (Rate: {}Hz)...", self.config.sample_rate);
            self.start_output_stream()?;
            self.start_input_stream()?;
            Ok(())
        }

        /// Stops BOTH streams.
        /// Call this when leaving a group.
        pub fn stop_session(&self) -> Result<(), AudioError> {
            log::info!("Stopping Audio Session...");
            // Now explicitly releases hardware immediately!
            self.release_resources();
            self.is_mic_enabled.store(false, Ordering::Relaxed);
            Ok(())
        }

        pub fn is_session_active(&self) -> bool {
            let input_active = self.input_stream.lock().unwrap().is_some();
            let output_active = self.output_stream.lock().unwrap().is_some();
            input_active && output_active
        }

        pub fn set_mic_enabled(&self, enabled: bool) {
            self.is_mic_enabled.store(enabled, Ordering::Relaxed);
            if enabled {
                log::info!("Microphone UNMUTED");
            } else {
                log::info!("Microphone MUTED");
            }
        }

        pub fn push_incoming_packet(&self, data: Vec<u8>) {
            if let Some((origin_id, seq, opus_data)) = unwrap_packet(&data) {
                // LOCK-FREE SEND: We lock mutex only to get the sender, then send non-blockingly
                if let Ok(guard) = self.packet_tx.lock() {
                    if let Some(tx) = &*guard {
                        let _ = tx.send((origin_id, seq, opus_data.to_vec()));
                    }
                }
            }
        }

        fn release_resources(&self) {
            // Clear the sender so incoming packets stop piling up
            if let Ok(mut guard) = self.packet_tx.lock() {
                *guard = None;
            }

            if let Ok(mut stream_opt) = self.input_stream.lock() {
                if let Some(mut stream) = stream_opt.take() {
                    let _ = stream.close();
                }
            }
            if let Ok(mut stream_opt) = self.output_stream.lock() {
                if let Some(mut stream) = stream_opt.take() {
                    let _ = stream.close();
                }
            }
        }

        fn start_input_stream(&self) -> Result<(), AudioError> {
            let samples_per_frame = (self.config.sample_rate / 1000 * self.config.frame_size_ms) as usize;
            let encoder_rate = map_sample_rate(self.config.sample_rate);

            let mut encoder = Encoder::new(encoder_rate, Channels::Mono, Application::Voip)
                .map_err(|_| AudioError::EncoderError)?;
            let _ = encoder.set_dtx(true);
            let _ = encoder.set_inband_fec(true);

            let callback = InputCallback {
                encoder,
                sequence_number: self.sequence_number.clone(),
                tx_transport: self.tx_transport.clone(),
                buffer: [0i16; MAX_BUFFER_SIZE],
                buffer_pos: 0,
                samples_per_frame,
                is_mic_enabled: self.is_mic_enabled.clone(),
                own_node_id: self.own_node_id,
                error_callback: self.error_callback.clone(),
            };

            // 1. Configure properties on the BASE builder first
            let mut builder = AudioStreamBuilder::default()
                .set_direction::<Input>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_sample_rate(self.config.sample_rate)
                .set_input_preset(InputPreset::VoiceCommunication);

            // 2. Set Device ID on the BASE builder (before setting callback)
            if self.config.input_device_id != 0 {
                log::info!("Input: Explicit Device ID {}", self.config.input_device_id);
                builder = builder.set_device_id(self.config.input_device_id);
            }

            // 3. Set Callback (Converts to Async Builder) and Open
            let mut stream = builder
                .set_callback(callback)
                .open_stream()
                .map_err(|e| {
                    log::error!("Open Input Stream Error: {}", e);
                    AudioError::DeviceError
                })?;

            stream.start().map_err(|_| AudioError::DeviceError)?;
            *self.input_stream.lock().unwrap() = Some(stream);
            Ok(())
        }

        fn start_output_stream(&self) -> Result<(), AudioError> {
            // Create lock-free channel
            let (tx, rx) = unbounded();

            // Update the sender for incoming packets
            *self.packet_tx.lock().unwrap() = Some(tx);

            // Give receiver to the callback (it owns the map now)
            let callback = OutputCallback {
                peers: HashMap::new(),
                packet_rx: rx,
                sample_rate: self.config.sample_rate,
                max_jitter_packets: (self.config.jitter_buffer_ms / self.config.frame_size_ms) as usize,
                error_callback: self.error_callback.clone(),
            };

            let mut builder = AudioStreamBuilder::default()
                .set_direction::<Output>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_sample_rate(self.config.sample_rate)
                .set_usage(Usage::VoiceCommunication);

            if self.config.output_device_id != 0 {
                log::info!("Output: Explicit Device ID {}", self.config.output_device_id);
                builder = builder.set_device_id(self.config.output_device_id);
            }

            let mut stream = builder
                .set_callback(callback)
                .open_stream()
                .map_err(|e| {
                    log::error!("Open Output Stream Error: {}", e);
                    AudioError::DeviceError
                })?;

            stream.start().map_err(|_| AudioError::DeviceError)?;
            *self.output_stream.lock().unwrap() = Some(stream);
            Ok(())
        }
    }

    // --- Callbacks ---

    struct InputCallback {
        encoder: Encoder,
        sequence_number: Arc<Mutex<u16>>,
        tx_transport: StdSender<Vec<u8>>,
        buffer: [i16; MAX_BUFFER_SIZE],
        buffer_pos: usize,
        samples_per_frame: usize,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
    }

    impl AudioInputCallback for InputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioInputStreamSafe, frames: &[i16]) -> DataCallbackResult {
            // 1. Copy data into our local buffer
            for &sample in frames {
                if self.buffer_pos < MAX_BUFFER_SIZE {
                    self.buffer[self.buffer_pos] = sample;
                    self.buffer_pos += 1;
                }
            }

            // 2. Process full frames
            while self.buffer_pos >= self.samples_per_frame {
                // Check the Gate!
                // If false, we process the buffer (to clear it) but DO NOT encode/send.
                let should_send = self.is_mic_enabled.load(Ordering::Relaxed);

                if should_send {
                    let chunk = &self.buffer[0..self.samples_per_frame];
                    let mut output_buffer = [0u8; OPUS_OUT_BUFFER_SIZE];

                    match self.encoder.encode(chunk, &mut output_buffer) {
                        Ok(len) => {
                            let mut seq = self.sequence_number.lock().unwrap();
                            let packet = wrap_packet(self.own_node_id, *seq, &output_buffer[..len]);
                            *seq = seq.wrapping_add(1);
                            let _ = self.tx_transport.send(packet);
                        },
                        Err(e) => { log::error!("Opus Encode Failed: {}", e); }
                    }
                } else {
                    // Optional: Reset encoder state or send silence if using DTX heavily,
                    // but for PTT, simply skipping encoding is most efficient.
                }

                // We want to keep everything from 'samples_per_frame' up to 'buffer_pos'
                // and move it to index 0.
                let remaining = self.buffer_pos - self.samples_per_frame;
                self.buffer.copy_within(self.samples_per_frame..self.buffer_pos, 0);
                self.buffer_pos = remaining;
            }
            DataCallbackResult::Continue
        }

        fn on_error_before_close(&mut self, _stream: &mut dyn AudioInputStreamSafe, error: oboe::Error) {
            self.error_callback.on_engine_error(error as i32);
        }
    }

    struct OutputCallback {
        peers: HashMap<u32, PeerStream>,
        packet_rx: Receiver<(u32, u16, Vec<u8>)>,
        sample_rate: i32,
        max_jitter_packets: usize,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
    }

    impl AudioOutputCallback for OutputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioOutputStreamSafe, frames: &mut [i16]) -> DataCallbackResult {
            // 1. Drain Channel (Lock-Free)
            while let Ok((id, seq, data)) = self.packet_rx.try_recv() {
                let rate = self.sample_rate;
                let peer = self.peers.entry(id).or_insert_with(|| PeerStream::new(rate));
                peer.jitter_buffer.insert(seq, data);
                peer.silence_counter = 0;
            }

            let samples_needed = frames.len();
            let mut mix_buffer = vec![0i32; samples_needed];
            let mut dead_peers = Vec::new();

            // 2. Process Peers (Local ownership, no mutex!)
            for (&node_id, peer) in self.peers.iter_mut() {
                peer.silence_counter += 1;
                if peer.silence_counter > PEER_TIMEOUT_FRAMES * 5 {
                     dead_peers.push(node_id);
                     continue;
                }

                let mut peer_samples_produced = 0;

                while peer_samples_produced < samples_needed {
                    // A. Use leftover decoded audio
                    if peer.buffer_len > 0 {
                        let to_copy = std::cmp::min(samples_needed - peer_samples_produced, peer.buffer_len);
                        for i in 0..to_copy {
                            mix_buffer[peer_samples_produced + i] += peer.buffer[i] as i32;
                        }

                        let remaining = peer.buffer_len - to_copy;
                        peer.buffer.copy_within(to_copy..peer.buffer_len, 0);
                        peer.buffer_len = remaining;
                        peer_samples_produced += to_copy;
                        continue;
                    }

                    // B. Jitter Buffer Maintenance
                    while peer.jitter_buffer.len() > self.max_jitter_packets {
                        if let Some(&first) = peer.jitter_buffer.keys().next() {
                            peer.jitter_buffer.remove(&first);
                            peer.next_expected_seq = Some(first.wrapping_add(1));
                        }
                    }

                    // C. Buffering Logic
                    if peer.buffering {
                        if peer.jitter_buffer.len() >= JITTER_BUFFER_START_THRESHOLD {
                            peer.buffering = false;
                            if let Some(&first) = peer.jitter_buffer.keys().next() {
                                peer.next_expected_seq = Some(first);
                            }
                        } else {
                            break; // Still buffering
                        }
                    }

                    // D. Fetch/Loss Logic
                    let mut packet_to_decode: Option<Option<Vec<u8>>> = None;

                    if let Some(expected) = peer.next_expected_seq {
                        if let Some(data) = peer.jitter_buffer.remove(&expected) {
                            // Happy Path
                            peer.next_expected_seq = Some(expected.wrapping_add(1));
                            packet_to_decode = Some(Some(data));
                        } else {
                            // Miss - Check lookahead window using constant
                            let has_future = peer.jitter_buffer.keys().any(|&k| {
                                let delta = k.wrapping_sub(expected);
                                delta > 0 && delta < JITTER_LOOKAHEAD_WINDOW
                            });

                            if has_future {
                                // Lost -> PLC
                                peer.next_expected_seq = Some(expected.wrapping_add(1));
                                packet_to_decode = Some(None);
                            } else if peer.jitter_buffer.is_empty() {
                                // Underrun
                                peer.buffering = true;
                                break;
                            } else {
                                // Gap -> Resync
                                if let Some(&next_avail) = peer.jitter_buffer.keys().next() {
                                    peer.next_expected_seq = Some(next_avail.wrapping_add(1));
                                    packet_to_decode = Some(Some(peer.jitter_buffer.remove(&next_avail).unwrap()));
                                }
                            }
                        }
                    }

                    // E. Decode
                    if let Some(maybe_data) = packet_to_decode {
                        let mut decoded_chunk = [0i16; MAX_BUFFER_SIZE];
                        let len = match maybe_data {
                            Some(data) => peer.decoder.decode(&data, &mut decoded_chunk, false).unwrap_or(0),
                            None => peer.decoder.decode(&[], &mut decoded_chunk, true).unwrap_or(0),
                        };

                        if len > 0 {
                            let space_left = samples_needed - peer_samples_produced;
                            let to_take = std::cmp::min(len, space_left);
                            for i in 0..to_take {
                                mix_buffer[peer_samples_produced + i] += decoded_chunk[i] as i32;
                            }
                            peer_samples_produced += to_take;
                            if len > to_take {
                                let remainder = len - to_take;
                                for i in 0..remainder {
                                    peer.buffer[i] = decoded_chunk[to_take + i];
                                }
                                peer.buffer_len = remainder;
                            }
                        }
                    } else {
                        break;
                    }
                }
            }

            for id in dead_peers {
                self.peers.remove(&id);
            }

            for i in 0..samples_needed {
                frames[i] = mix_buffer[i].clamp(i16::MIN as i32, i16::MAX as i32) as i16;
            }

            DataCallbackResult::Continue
        }

        fn on_error_before_close(&mut self, _stream: &mut dyn AudioOutputStreamSafe, error: oboe::Error) {
            self.error_callback.on_engine_error(error as i32);
        }
    }

    #[uniffi::export]
    pub fn init_logger() {
        android_logger::init_once(
            android_logger::Config::default().with_max_level(log::LevelFilter::Debug),
        );
    }
}

// ===========================================================================
// STUB IMPLEMENTATION (NON-ANDROID)
// ===========================================================================

#[cfg(not(target_os = "android"))]
mod stub_impl {
    use super::*;
    #[derive(uniffi::Object)]
    pub struct AudioEngine;
    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(_c: AudioConfig, _t: Box<dyn PacketTransport>, _cb: Box<dyn AudioErrorCallback>, _id: u32) -> Self { Self }
        pub fn start_session(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn stop_session(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn is_session_active(&self) -> bool { false }
        pub fn set_mic_enabled(&self, _e: bool) {}
        pub fn push_incoming_packet(&self, _d: Vec<u8>) {}
    }
    #[uniffi::export]
    pub fn init_logger() {}
}

#[cfg(target_os = "android")]
pub use real_impl::{AudioEngine, init_logger};
#[cfg(not(target_os = "android"))]
pub use stub_impl::{AudioEngine, init_logger};