uniffi::setup_scaffolding!("walkie_talkie_engine");

// Import our new Core module
mod audio_core;
// Only import what is needed for the interface
use audio_core::AudioConfig;

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
    use std::sync::{Arc, Mutex};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::Sender as StdSender;
    use std::sync::mpsc::{channel, Receiver as StdReceiver};
    use std::collections::HashMap;
    use crossbeam_channel::{unbounded, Receiver, Sender};
    use crate::audio_core::{RemoteStream, wrap_packet, unwrap_packet, PCM_BUFFER_SIZE};

    use oboe::{
        AudioInputCallback, AudioOutputCallback, AudioStreamBuilder, AudioStreamAsync,
        PerformanceMode, SharingMode, Mono, DataCallbackResult, InputPreset, Usage,
        Input, Output, AudioInputStreamSafe, AudioOutputStreamSafe, AudioStream
    };
    use opus_codec::{Encoder, Application, Channels, SampleRate};

    const OPUS_OUT_BUFFER_SIZE: usize = 512;

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

    #[derive(uniffi::Object)]
    pub struct AudioEngine {
        // Mutex guards for Oboe Streams.
        // Note: We only lock these to Start/Stop. The callbacks run on Oboe's thread and don't need these locks.
        input_stream: Mutex<Option<AudioStreamAsync<Input, InputCallback>>>,
        output_stream: Mutex<Option<AudioStreamAsync<Output, OutputCallback>>>,

        // Channel to send encoded packets to Kotlin (via PacketTransport)
        tx_transport: StdSender<Vec<u8>>,

        // Channel to receive incoming packets from Kotlin
        packet_tx: Mutex<Option<Sender<(u32, u16, Vec<u8>)>>>,

        sequence_number: Arc<Mutex<u16>>,
        config: AudioConfig,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
    }

    // Auto-cleanup when the Kotlin object is garbage collected
    impl Drop for AudioEngine {
        fn drop(&mut self) {
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

        pub fn start_session(&self) -> Result<(), AudioError> {
            log::info!("Starting Audio Session (Rate: {}Hz)...", self.config.sample_rate);
            self.start_output_stream()?;
            self.start_input_stream()?;
            Ok(())
        }

        pub fn stop_session(&self) -> Result<(), AudioError> {
            log::info!("Stopping Audio Session...");
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
                if let Ok(guard) = self.packet_tx.lock() {
                    if let Some(tx) = &*guard {
                        let _ = tx.send((origin_id, seq, opus_data.to_vec()));
                    }
                }
            }
        }

        fn release_resources(&self) {
            // Close Oboe streams to release Microphone/Speaker hardware locks
            if let Ok(mut stream) = self.input_stream.lock() {
                if let Some(mut s) = stream.take() { let _ = s.close(); }
            }
            if let Ok(mut stream) = self.output_stream.lock() {
                if let Some(mut s) = stream.take() { let _ = s.close(); }
            }
        }

        fn start_input_stream(&self) -> Result<(), AudioError> {
            let samples_per_frame = (self.config.sample_rate / 1000 * self.config.frame_size_ms) as usize;
            let encoder_rate = map_sample_rate(self.config.sample_rate);
            let mut encoder = Encoder::new(encoder_rate, Channels::Mono, Application::Voip)
                .map_err(|_| AudioError::EncoderError)?;
            let _ = encoder.set_dtx(true);

            let callback = InputCallback {
                encoder,
                sequence_number: self.sequence_number.clone(),
                tx_transport: self.tx_transport.clone(),
                buffer: [0i16; PCM_BUFFER_SIZE],
                buffer_pos: 0,
                samples_per_frame,
                is_mic_enabled: self.is_mic_enabled.clone(),
                own_node_id: self.own_node_id,
                error_callback: self.error_callback.clone(),
            };

            let mut builder = AudioStreamBuilder::default()
                .set_direction::<Input>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_sample_rate(self.config.sample_rate)
                .set_input_preset(InputPreset::VoiceCommunication);

            if self.config.input_device_id != 0 {
                builder = builder.set_device_id(self.config.input_device_id);
            }

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
            let (tx, rx) = unbounded();
            *self.packet_tx.lock().unwrap() = Some(tx);

            // FIX: Initialize with a reasonable default capacity to avoid initial reallocs
            let initial_capacity = 4096;

            let callback = OutputCallback {
                peers: HashMap::new(),
                packet_rx: rx,
                sample_rate: self.config.sample_rate,
                jitter_buffer_ms: self.config.jitter_buffer_ms,
                frame_size_ms: self.config.frame_size_ms,
                error_callback: self.error_callback.clone(),
                mix_buffer: Vec::with_capacity(initial_capacity),
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

    // --- Input Callback (Microphone) ---
    struct InputCallback {
        encoder: Encoder,
        sequence_number: Arc<Mutex<u16>>,
        tx_transport: StdSender<Vec<u8>>,
        buffer: [i16; PCM_BUFFER_SIZE],
        buffer_pos: usize,
        samples_per_frame: usize,
        is_mic_enabled: Arc<AtomicBool>,
        own_node_id: u32,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
    }

    impl AudioInputCallback for InputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioInputStreamSafe, frames: &[i16]) -> DataCallbackResult {
            // Buffer management: Simply copy incoming samples to our internal buffer.
            for &sample in frames {
                if self.buffer_pos < PCM_BUFFER_SIZE {
                    self.buffer[self.buffer_pos] = sample;
                    self.buffer_pos += 1;
                }
            }

            // Once we have enough for a frame (60ms), encode it.
            while self.buffer_pos >= self.samples_per_frame {
                let should_send = self.is_mic_enabled.load(Ordering::Relaxed);

                if should_send {
                    let chunk = &self.buffer[0..self.samples_per_frame];
                    let mut output_buffer = [0u8; OPUS_OUT_BUFFER_SIZE];

                    if let Ok(len) = self.encoder.encode(chunk, &mut output_buffer) {
                        let mut seq = self.sequence_number.lock().unwrap();
                        let packet = wrap_packet(self.own_node_id, *seq, &output_buffer[..len]);
                        *seq = seq.wrapping_add(1);
                        // Send to Kotlin (Network Layer)
                        let _ = self.tx_transport.send(packet);
                    }
                }

                // Shift buffer
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

    // --- Output Callback (Speaker) ---
    struct OutputCallback {
        peers: HashMap<u32, RemoteStream>,
        packet_rx: Receiver<(u32, u16, Vec<u8>)>,
        sample_rate: i32,
        jitter_buffer_ms: i32,
        frame_size_ms: i32,
        error_callback: Arc<Box<dyn AudioErrorCallback>>,
        mix_buffer: Vec<i32>,
    }

    impl AudioOutputCallback for OutputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioOutputStreamSafe, frames: &mut [i16]) -> DataCallbackResult {
            // 1. Ingest Packets: Move data from Channel -> Peer Jitter Buffers
            while let Ok((id, seq, data)) = self.packet_rx.try_recv() {
                let peer = self.peers.entry(id).or_insert_with(|| {
                    RemoteStream::new(self.sample_rate, self.jitter_buffer_ms, self.frame_size_ms)
                });
                peer.push_packet(seq, data);
            }

            let samples_needed = frames.len();

            // FIX: Resize buffer only if Oboe asks for more than usual (Rare)
            // This prevents heap allocation in steady state.
            if self.mix_buffer.len() < samples_needed {
                self.mix_buffer.resize(samples_needed, 0);
            }
            // Zero out the buffer for this cycle (memset is fast)
            self.mix_buffer[..samples_needed].fill(0);

            let mut dead_peers = Vec::new();
            const PEER_TIMEOUT_FRAMES: usize = 50; // ~3 seconds @ 60ms frames

            // 2. Mixing Logic using RemoteStream (Jitter Buffer)
            for (&node_id, peer) in self.peers.iter_mut() {
                // Garbage Collection Check
                peer.silence_counter += 1;
                if peer.silence_counter > PEER_TIMEOUT_FRAMES * 5 {
                     dead_peers.push(node_id);
                     continue;
                }

                let mut peer_samples_produced = 0;

                // SAFETY: Infinite Loop Protection
                // We should never loop more than a few times to fill a small Oboe buffer.
                let mut loop_safety_counter = 0;

                while peer_samples_produced < samples_needed {
                    if loop_safety_counter > 10 {
                        log::error!("Audio Engine Panic: Infinite Loop in Mixing Logic!");
                        break;
                    }
                    loop_safety_counter += 1;

                    // A. Use leftovers from previous frame
                    if peer.valid_samples > 0 {
                        let to_copy = std::cmp::min(samples_needed - peer_samples_produced, peer.valid_samples);
                        for i in 0..to_copy {
                            // FIX: Accumulate into reusable buffer
                            self.mix_buffer[peer_samples_produced + i] += peer.pcm_buffer[i] as i32;
                        }

                        // Shift remaining data to start of buffer
                        let remaining = peer.valid_samples - to_copy;
                        peer.pcm_buffer.copy_within(to_copy..peer.valid_samples, 0);
                        peer.valid_samples = remaining;
                        peer_samples_produced += to_copy;
                        continue; // Go back to check if we filled the need
                    }

                    // B. If we need more data, try to decode next packet
                    if peer.process_next_frame() {
                        // process_next_frame() filled peer.pcm_buffer and set valid_samples
                        // Loop will restart, hit Case A, and consume it.
                        continue;
                    } else {
                        // C. No data available (Buffering or Empty).
                        // We contribute silence for the rest of this frame.
                        break;
                    }
                }
            }

            // 3. Remove Dead Peers
            for id in dead_peers {
                self.peers.remove(&id);
            }

            // 4. Downmix 32-bit -> 16-bit (Clamping)
            for i in 0..samples_needed {
                frames[i] = self.mix_buffer[i].clamp(i16::MIN as i32, i16::MAX as i32) as i16;
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

#[cfg(target_os = "android")]
pub use real_impl::{AudioEngine, init_logger};

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

#[cfg(not(target_os = "android"))]
pub use stub_impl::{AudioEngine, init_logger};