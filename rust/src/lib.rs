use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::collections::BTreeMap;

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

// --- Configuration Struct ---
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

    const MAX_BUFFER_SIZE: usize = 5760;

    fn wrap_packet(seq: u16, opus_data: &[u8]) -> Vec<u8> {
        let mut packet = Vec::with_capacity(2 + opus_data.len());
        let mut buf = [0u8; 2];
        LittleEndian::write_u16(&mut buf, seq);
        packet.extend_from_slice(&buf);
        packet.extend_from_slice(opus_data);
        packet
    }

    fn unwrap_packet(data: &[u8]) -> Option<(u16, &[u8])> {
        if data.len() < 3 { return None; }
        let seq = LittleEndian::read_u16(&data[0..2]);
        Some((seq, &data[2..]))
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
    }

    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(config: AudioConfig, transport: Box<dyn PacketTransport>) -> Self {
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
            }
        }

        /// Starts BOTH Input and Output streams.
        /// Call this when joining a group.
        pub fn start_session(&self) -> Result<(), AudioError> {
            log::info!("Starting Audio Session (Hot Mic Architecture)...");
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

            let decoder = Decoder::new(SampleRate::Hz48000, Channels::Mono)
                .map_err(|_| AudioError::DecoderError)?;

            let callback = OutputCallback {
                decoder,
                incoming_rx: rx,
                jitter_buffer: BTreeMap::new(),
                buffer: [0i16; MAX_BUFFER_SIZE],
                buffer_pos: 0,
                next_expected_seq: None,
                buffering: true,
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
                            let packet = wrap_packet(*seq, &output_buffer[..len]);
                            *seq = seq.wrapping_add(1);
                            let _ = self.tx_transport.send(packet);
                        },
                        Err(e) => { log::error!("Opus Encode Failed: {}", e); }
                    }
                } else {
                    // Optional: Reset encoder state or send silence if using DTX heavily,
                    // but for PTT, simply skipping encoding is most efficient.
                }

                // Shift buffer
                let remaining = self.buffer_pos - self.samples_per_frame;
                unsafe {
                    std::ptr::copy(
                        self.buffer.as_ptr().add(self.samples_per_frame),
                        self.buffer.as_mut_ptr(),
                        remaining
                    );
                }
                self.buffer_pos = remaining;
            }
            DataCallbackResult::Continue
        }
    }

    struct OutputCallback {
        decoder: Decoder,
        incoming_rx: CbReceiver<Vec<u8>>,
        jitter_buffer: BTreeMap<u16, Vec<u8>>,
        buffer: [i16; MAX_BUFFER_SIZE],
        buffer_pos: usize,
        next_expected_seq: Option<u16>,
        buffering: bool,
        max_jitter_packets: usize,
    }

    impl AudioOutputCallback for OutputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioOutputStreamSafe, frames: &mut [i16]) -> DataCallbackResult {
            // 1. Drain Channel (Pull from Kotlin/BLE)
            while let Ok(packet_data) = self.incoming_rx.try_recv() {
                if let Some((seq, opus_data)) = unwrap_packet(&packet_data) {
                    self.jitter_buffer.insert(seq, opus_data.to_vec());
                }
            }

            // 2. Fill Audio Buffer
            let samples_needed = frames.len();
            let mut samples_filled = 0;

            while samples_filled < samples_needed {
                // If we have leftover decoded audio from previous frame, use it first
                if self.buffer_pos > 0 {
                    let to_copy = std::cmp::min(samples_needed - samples_filled, self.buffer_pos);
                    for i in 0..to_copy {
                        frames[samples_filled + i] = self.buffer[i];
                    }

                    // Shift remaining buffer data to the start (memmove)
                    let remaining = self.buffer_pos - to_copy;
                    unsafe {
                        std::ptr::copy(
                            self.buffer.as_ptr().add(to_copy),
                            self.buffer.as_mut_ptr(),
                            remaining
                        );
                    }
                    self.buffer_pos = remaining;
                    samples_filled += to_copy;
                    continue;
                }

                // Overflow Protection: If buffer is too big, drop oldest
                while self.jitter_buffer.len() > self.max_jitter_packets {
                    if let Some(&first) = self.jitter_buffer.keys().next() {
                        // log::warn!("[Audio] Overflow! Dropping {}", first);
                        self.jitter_buffer.remove(&first);
                        self.next_expected_seq = Some(first.wrapping_add(1));
                    }
                }

                // Buffering Logic (Wait until we have enough packets to start)
                if self.buffering {
                    if self.jitter_buffer.len() >= 6 {
                        self.buffering = false;
                        if let Some(&first_seq) = self.jitter_buffer.keys().next() {
                            self.next_expected_seq = Some(first_seq);
                            log::info!("[Audio] Buffering Complete. Resuming at Seq {}", first_seq);
                        }
                    } else {
                        // Still buffering, output silence
                        let remaining = samples_needed - samples_filled;
                        for i in 0..remaining { frames[samples_filled + i] = 0; }
                        return DataCallbackResult::Continue;
                    }
                }

                // Smart Packet Selection
                let packet_to_decode = if let Some(expected) = self.next_expected_seq {
                    if let Some(data) = self.jitter_buffer.remove(&expected) {
                        // Happy Path: We found the exact packet we wanted
                        self.next_expected_seq = Some(expected.wrapping_add(1));
                        Some(Some(data))
                    } else {
                        // MISSING PACKET LOGIC
                        // Check if we have future packets (e.g. expected+1, expected+2)
                        // This tells us if "expected" is truly lost or just late.

                        let has_future = self.jitter_buffer.contains_key(&expected.wrapping_add(1))
                                      || self.jitter_buffer.contains_key(&expected.wrapping_add(2));

                        if has_future {
                            // It's definitely lost. Use PLC.
                            log::debug!("[Audio] Packet {} LOST (Future arrived). Doing PLC.", expected);
                            self.next_expected_seq = Some(expected.wrapping_add(1));
                            Some(None) // Trigger PLC
                        } else {
                            // We don't have the next packet either. Buffer might be empty or everything is delayed.
                            // If buffer is empty, go back to buffering.
                            if self.jitter_buffer.is_empty() {
                                log::debug!("[Audio] Underrun. Re-buffering...");
                                self.buffering = true;
                                None
                            } else {
                                // Buffer has data, but it's way in the future (gap > 2).
                                // OR we just haven't received expected+1 yet.
                                // For simplicity, if we have data but not the immediate next few, resync.
                                if let Some(&next_available) = self.jitter_buffer.keys().next() {
                                     log::warn!("[Audio] Resync: Jump {} -> {}", expected, next_available);
                                     self.next_expected_seq = Some(next_available.wrapping_add(1));
                                     let data = self.jitter_buffer.remove(&next_available);
                                     data.map(Some)
                                } else {
                                     // Should be covered by is_empty() check, but safe fallback
                                     self.buffering = true;
                                     None
                                }
                            }
                        }
                    }
                } else {
                    None
                };

                // Decode Logic
                if let Some(maybe_data) = packet_to_decode {
                    let mut decoded_chunk = [0i16; MAX_BUFFER_SIZE];
                    let len = match maybe_data {
                        Some(data) => {
                            // Decode real audio
                            self.decoder.decode(&data, &mut decoded_chunk, false).unwrap_or(0)
                        },
                        None => {
                            // Decode "Loss" (PLC) - Opus invents audio based on previous frames
                            self.decoder.decode(&[], &mut decoded_chunk, true).unwrap_or(0)
                        }
                    };

                    if len > 0 {
                        for i in 0..len {
                            self.buffer[self.buffer_pos + i] = decoded_chunk[i];
                        }
                        self.buffer_pos += len;
                    }
                } else {
                    // Should not happen often, fill rest with silence
                    let remaining = samples_needed - samples_filled;
                    for i in 0..remaining { frames[samples_filled + i] = 0; }
                    break;
                }
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
        pub fn new(_config: AudioConfig, _transport: Box<dyn PacketTransport>) -> Self { Self }
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