use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::collections::{BTreeMap, HashMap};

uniffi::setup_scaffolding!("walkie_talkie_engine");

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
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48000,
            frame_size_ms: 60,
            jitter_buffer_ms: 1000,
        }
    }
}

#[uniffi::export(callback_interface)]
pub trait PacketTransport: Send + Sync {
    fn send_packet(&self, data: Vec<u8>);
}

#[cfg(target_os = "android")]
mod real_impl {
    use super::*;
    use std::thread;
    use std::sync::mpsc::{channel, Receiver};
    use byteorder::{ByteOrder, LittleEndian};
    use crossbeam_channel::{bounded, Sender as CbSender, Receiver as CbReceiver};

    use oboe::{
        AudioInputCallback, AudioOutputCallback, AudioStreamBuilder, AudioStreamAsync,
        PerformanceMode, SharingMode, Mono, DataCallbackResult, InputPreset, Usage,
        Input, Output, AudioInputStreamSafe, AudioOutputStreamSafe,
        AudioStream
    };
    use opus_codec::{Encoder, Decoder, Application, Channels, SampleRate};

    const MAX_BUFFER_SIZE: usize = 5760; // Enough for 120ms at 48kHz
    const PEER_TIMEOUT_FRAMES: usize = 50; // ~3 seconds cleanup

    // --- PACKET FORMATTING ---
    // Header: [OriginID (4 bytes)] [Sequence (2 bytes)]

    fn wrap_packet(origin_id: u32, seq: u16, opus_data: &[u8]) -> Vec<u8> {
        let mut packet = Vec::with_capacity(6 + opus_data.len());
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
        if data.len() < 6 { return None; }
        let origin_id = LittleEndian::read_u32(&data[0..4]);
        let seq = LittleEndian::read_u16(&data[4..6]);
        Some((origin_id, seq, &data[6..]))
    }

    // --- PER-PEER STATE ---
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
        fn new() -> Self {
            let decoder = Decoder::new(SampleRate::Hz48000, Channels::Mono).unwrap();
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
        tx_transport: Sender<Vec<u8>>,
        sequence_number: Arc<Mutex<u16>>,
        incoming_tx: Mutex<Option<CbSender<Vec<u8>>>>,
        config: AudioConfig,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
    }

    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(config: AudioConfig, transport: Box<dyn PacketTransport>, own_node_id: i32) -> Self {
            let (tx, rx): (Sender<Vec<u8>>, Receiver<Vec<u8>>) = channel();

            // Background thread to handle sending packets to Kotlin
            thread::spawn(move || {
                while let Ok(packet) = rx.recv() {
                    transport.send_packet(packet);
                }
            });

            Self {
                input_stream: Mutex::new(None),
                output_stream: Mutex::new(None),
                tx_transport: tx,
                sequence_number: Arc::new(Mutex::new(0)),
                incoming_tx: Mutex::new(None),
                config,
                is_mic_enabled: Arc::new(AtomicBool::new(false)),
                own_node_id: own_node_id as u32,
            }
        }

        /// Starts BOTH Input and Output streams.
        /// Call this when joining a group.
        pub fn start_session(&self) -> Result<(), AudioError> {
            log::info!("Starting Audio Session (Multi-Stream Mixing)...");
            self.start_output_stream()?;
            self.start_input_stream()?;
            Ok(())
        }

        /// Stops BOTH streams.
        /// Call this when leaving a group.
        pub fn stop_session(&self) -> Result<(), AudioError> {
            log::info!("Stopping Audio Session...");
            *self.input_stream.lock().unwrap() = None;
            *self.output_stream.lock().unwrap() = None;
            *self.incoming_tx.lock().unwrap() = None;
            self.is_mic_enabled.store(false, Ordering::Relaxed);
            Ok(())
        }

        // --- SSOT Methods ---

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
            let tx_guard = self.incoming_tx.lock().unwrap();
            if let Some(tx) = tx_guard.as_ref() {
                let _ = tx.try_send(data);
            }
        }

        // --- Internal Helpers ---

        fn start_input_stream(&self) -> Result<(), AudioError> {
            let samples_per_frame = (self.config.sample_rate / 1000 * self.config.frame_size_ms) as usize;

            let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)
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
            };

            let mut stream = AudioStreamBuilder::default()
                .set_direction::<Input>()
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_sample_rate(self.config.sample_rate)
                .set_input_preset(InputPreset::VoiceCommunication)
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
            let (tx, rx) = bounded(200);
            *self.incoming_tx.lock().unwrap() = Some(tx);

            let callback = OutputCallback {
                incoming_rx: rx,
                peers: HashMap::new(),
                max_jitter_packets: (self.config.jitter_buffer_ms / self.config.frame_size_ms) as usize,
            };

            let mut stream = AudioStreamBuilder::default()
                .set_direction::<Output>()
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_sample_rate(self.config.sample_rate)
                .set_usage(Usage::VoiceCommunication)
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

    struct InputCallback {
        encoder: Encoder,
        sequence_number: Arc<Mutex<u16>>,
        tx_transport: Sender<Vec<u8>>,
        buffer: [i16; MAX_BUFFER_SIZE],
        buffer_pos: usize,
        samples_per_frame: usize,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
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
                    let mut output_buffer = [0u8; 512];

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
    }

    struct OutputCallback {
        incoming_rx: CbReceiver<Vec<u8>>,
        peers: HashMap<u32, PeerStream>,
        max_jitter_packets: usize,
    }

    impl AudioOutputCallback for OutputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioOutputStreamSafe, frames: &mut [i16]) -> DataCallbackResult {
            // 1. Drain Channel (Pull from Kotlin/BLE)
            while let Ok(packet_data) = self.incoming_rx.try_recv() {
                if let Some((origin_id, seq, opus_data)) = unwrap_packet(&packet_data) {
                    let peer = self.peers.entry(origin_id).or_insert_with(PeerStream::new);
                    peer.jitter_buffer.insert(seq, opus_data.to_vec());
                    peer.silence_counter = 0;
                }
            }

            // 2. Prepare Mixer Buffer (i32 to avoid overflow)
            let samples_needed = frames.len();
            let mut mix_buffer = vec![0i32; samples_needed];

            // 3. Process each Peer
            let mut dead_peers = Vec::new();

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
                        if peer.jitter_buffer.len() >= 6 {
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
                            // Miss
                            let has_future = peer.jitter_buffer.keys().any(|&k| k > expected && k < expected.wrapping_add(10));
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
                            None => peer.decoder.decode(&[], &mut decoded_chunk, true).unwrap_or(0), // PLC
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

            // 4. Cleanup
            for id in dead_peers {
                self.peers.remove(&id);
            }

            // 5. Clamping & Output
            for i in 0..samples_needed {
                frames[i] = mix_buffer[i].clamp(i16::MIN as i32, i16::MAX as i32) as i16;
            }

            DataCallbackResult::Continue
        }
    }

    #[uniffi::export]
    pub fn init_logger() {
        android_logger::init_once(
            android_logger::Config::default().with_max_level(log::LevelFilter::Debug),
        );
    }
}

#[cfg(not(target_os = "android"))]
mod stub_impl {
    use super::*;
    #[derive(uniffi::Object)]
    pub struct AudioEngine;
    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(_config: AudioConfig, _transport: Box<dyn PacketTransport>, _id: i32) -> Self { Self }
        pub fn start_session(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn stop_session(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn is_session_active(&self) -> bool { false }
        pub fn set_mic_enabled(&self, _enabled: bool) {}
        pub fn push_incoming_packet(&self, _data: Vec<u8>) {}
    }
    #[uniffi::export]
    pub fn init_logger() {}
}

#[cfg(target_os = "android")]
pub use real_impl::{AudioEngine, init_logger};
#[cfg(not(target_os = "android"))]
pub use stub_impl::{AudioEngine, init_logger};