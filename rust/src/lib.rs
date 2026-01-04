use std::sync::{Arc, Mutex};
use std::sync::mpsc::Sender;

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

#[uniffi::export(callback_interface)]
pub trait PacketTransport: Send + Sync {
    fn send_packet(&self, data: Vec<u8>);
}

#[cfg(target_os = "android")]
mod real_impl {
    use super::*;
    use std::collections::BTreeMap;
    use std::thread;
    use std::sync::mpsc::{channel, Receiver};
    use byteorder::{ByteOrder, LittleEndian};

    use oboe::{
        AudioInputCallback, AudioOutputCallback, AudioStreamBuilder, AudioStreamAsync,
        PerformanceMode, SharingMode, Mono, DataCallbackResult, InputPreset, Usage,
        Input, Output, AudioInputStreamSafe, AudioOutputStreamSafe,
        AudioStream
    };
    use opus_codec::{Encoder, Decoder, Application, Channels, SampleRate};

    const FRAME_SIZE_MS: i32 = 40;
    const SAMPLE_RATE_INT: i32 = 48000;
    const SAMPLES_PER_FRAME: usize = (SAMPLE_RATE_INT / 1000 * FRAME_SIZE_MS) as usize;

    const MIN_BUFFER_TO_START: usize = 4;
    const MAX_BUFFER_PACKETS: usize = 50;

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
        incoming_queue: Arc<Mutex<BTreeMap<u16, Vec<u8>>>>,
    }

    #[uniffi::export]
    impl AudioEngine {
        #[uniffi::constructor]
        pub fn new(transport: Box<dyn PacketTransport>) -> Self {
            let (tx, rx): (Sender<Vec<u8>>, Receiver<Vec<u8>>) = channel();
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
                incoming_queue: Arc::new(Mutex::new(BTreeMap::new())),
            }
        }

        pub fn start_recording(&self) -> Result<(), AudioError> {
            log::info!("Starting Recording (Mono Request)...");

            // 1. Initialize Encoder
            let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)
                .map_err(|_| AudioError::EncoderError)?;
            let _ = encoder.set_dtx(true);
            let _ = encoder.set_inband_fec(true);

            // 2. Setup Callback
            let callback = InputCallback {
                encoder,
                sequence_number: self.sequence_number.clone(),
                tx_transport: self.tx_transport.clone(),
                buffer: Vec::with_capacity(SAMPLES_PER_FRAME * 2),
            };

            // 3. Request MONO explicitly
            let mut stream = AudioStreamBuilder::default()
                .set_direction::<Input>()
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_sample_rate(SAMPLE_RATE_INT)
                .set_input_preset(InputPreset::VoiceCommunication)
                .set_callback(callback)
                .open_stream()
                .map_err(|e| {
                    log::error!("Open Input Stream Error: {}", e);
                    AudioError::DeviceError
                })?;

            stream.start().map_err(|_| AudioError::DeviceError)?;
            *self.input_stream.lock().unwrap() = Some(stream);
            self.ensure_output_running()?;
            Ok(())
        }

        pub fn stop_recording(&self) -> Result<(), AudioError> {
            log::info!("Stopping Recording...");
            *self.input_stream.lock().unwrap() = None;
            Ok(())
        }

        pub fn push_incoming_packet(&self, data: Vec<u8>) {
            if let Some((seq, opus_data)) = unwrap_packet(&data) {
                let mut queue = self.incoming_queue.lock().unwrap();
                queue.insert(seq, opus_data.to_vec());

                if queue.len() > MAX_BUFFER_PACKETS {
                    if let Some(&first_key) = queue.keys().next() {
                        queue.remove(&first_key);
                    }
                }
                drop(queue);
                let _ = self.ensure_output_running();
            }
        }

        pub fn shutdown(&self) {
            *self.input_stream.lock().unwrap() = None;
            *self.output_stream.lock().unwrap() = None;
        }

        fn ensure_output_running(&self) -> Result<(), AudioError> {
            let mut out_lock = self.output_stream.lock().unwrap();
            if out_lock.is_some() { return Ok(()); }

            log::info!("Starting Playback Stream...");

            let decoder = Decoder::new(SampleRate::Hz48000, Channels::Mono)
                .map_err(|_| AudioError::DecoderError)?;

            let callback = OutputCallback {
                decoder,
                queue: self.incoming_queue.clone(),
                buffer: Vec::with_capacity(SAMPLES_PER_FRAME * 2),
                next_expected_seq: None,
                buffering: true,
            };

            let mut stream = AudioStreamBuilder::default()
                .set_direction::<Output>()
                .set_format::<i16>()
                .set_channel_count::<Mono>()
                .set_performance_mode(PerformanceMode::None)
                .set_sharing_mode(SharingMode::Shared)
                .set_sample_rate(SAMPLE_RATE_INT)
                .set_usage(Usage::VoiceCommunication)
                .set_callback(callback)
                .open_stream()
                .map_err(|e| {
                    log::error!("Open Output Stream Error: {}", e);
                    AudioError::DeviceError
                })?;

            stream.start().map_err(|_| AudioError::DeviceError)?;
            *out_lock = Some(stream);
            Ok(())
        }
    }

    struct InputCallback {
        encoder: Encoder,
        sequence_number: Arc<Mutex<u16>>,
        tx_transport: Sender<Vec<u8>>,
        buffer: Vec<i16>,
    }

    impl AudioInputCallback for InputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioInputStreamSafe, frames: &[i16]) -> DataCallbackResult {

            const GAIN_MULTIPLIER: f32 = 1.0;

            for sample in frames {
                let boosted = (*sample as f32 * GAIN_MULTIPLIER) as i32;
                let clamped = boosted.clamp(-32768, 32767) as i16;
                self.buffer.push(clamped);
            }

            // Packetize logic (Standard Opus encoding)
            while self.buffer.len() >= SAMPLES_PER_FRAME {
                let frame_data: Vec<i16> = self.buffer.drain(0..SAMPLES_PER_FRAME).collect();
                let mut output_buffer = [0u8; 512];

                match self.encoder.encode(&frame_data, &mut output_buffer) {
                    Ok(len) => {
                        let mut seq = self.sequence_number.lock().unwrap();
                        let packet = wrap_packet(*seq, &output_buffer[..len]);
                        *seq = seq.wrapping_add(1);
                        let _ = self.tx_transport.send(packet);
                    },
                    Err(e) => { log::error!("Opus Encode Failed: {}", e); }
                }
            }
            DataCallbackResult::Continue
        }
    }

    struct OutputCallback {
        decoder: Decoder,
        queue: Arc<Mutex<BTreeMap<u16, Vec<u8>>>>,
        buffer: Vec<i16>,
        next_expected_seq: Option<u16>,
        buffering: bool,
    }

    impl AudioOutputCallback for OutputCallback {
        type FrameType = (i16, Mono);

        fn on_audio_ready(&mut self, _stream: &mut dyn AudioOutputStreamSafe, frames: &mut [i16]) -> DataCallbackResult {
            let samples_needed = frames.len();
            let mut samples_filled = 0;

            while samples_filled < samples_needed {
                if self.buffer.is_empty() {
                    let action: Option<Option<Vec<u8>>> = {
                        let mut queue = self.queue.lock().unwrap();

                        // 1. Overflow Protection
                        while queue.len() > 25 {
                            if let Some(&first) = queue.keys().next() {
                                queue.remove(&first);
                                self.next_expected_seq = Some(first.wrapping_add(1));
                                log::warn!("[AudioTrace] JitterBuffer Overflow! Dropped packet {}", first);
                            }
                        }

                        // 2. Buffering Logic
                        if self.buffering {
                            if queue.len() >= MIN_BUFFER_TO_START {
                                self.buffering = false;
                                if let Some(&first_seq) = queue.keys().next() {
                                    self.next_expected_seq = Some(first_seq);
                                    log::info!("[AudioTrace] Buffering Complete. Starting at Seq {}", first_seq);
                                }
                            }
                        }

                        if self.buffering {
                            None
                        } else {
                            if let Some(expected) = self.next_expected_seq {
                                if let Some(data) = queue.remove(&expected) {
                                    // CASE A: Perfect Packet
                                    self.next_expected_seq = Some(expected.wrapping_add(1));
                                    Some(Some(data))
                                } else {
                                    // CASE B: Packet Missing
                                    if let Some(&next_available) = queue.keys().next() {
                                        let gap = next_available.wrapping_sub(expected);

                                        // FIX: Improved Reset Logic
                                        if gap > 3000 {
                                            log::info!("[AudioTrace] Seq Reset Detected! Jump {} -> {}", expected, next_available);
                                            self.next_expected_seq = Some(next_available.wrapping_add(1));
                                            let data = queue.remove(&next_available);
                                            data.map(Some)
                                        } else if gap > 0 {
                                            // Small gap: PLC (Silence)
                                            self.next_expected_seq = Some(expected.wrapping_add(1));
                                            Some(None)
                                        } else {
                                            None
                                        }
                                    } else {
                                        // Queue empty -> Re-enter buffering
                                        log::info!("[AudioTrace] Queue Drained. Buffering...");
                                        self.buffering = true;
                                        None
                                    }
                                }
                            } else {
                                None
                            }
                        }
                    };

                    if let Some(maybe_packet) = action {
                        let mut decoded_chunk = [0i16; SAMPLES_PER_FRAME];

                        let len = match maybe_packet {
                            Some(packet) => {
                                match self.decoder.decode(&packet, &mut decoded_chunk, false) {
                                    Ok(size) => size,
                                    Err(e) => {
                                        log::error!("Opus Decode Error: {:?}", e);
                                        0
                                    }
                                }
                            },
                            None => {
                                // PLC
                                match self.decoder.decode(&[], &mut decoded_chunk, true) {
                                    Ok(size) => size,
                                    Err(_) => 0
                                }
                            }
                        };

                        if len > 0 {
                            self.buffer.extend_from_slice(&decoded_chunk[..len]);
                        } else {
                            self.buffer.extend(std::iter::repeat(0).take(SAMPLES_PER_FRAME));
                        }
                    } else {
                        // Output Silence while buffering
                        let remaining = samples_needed - samples_filled;
                        for i in 0..remaining { frames[samples_filled + i] = 0; }
                        samples_filled += remaining;
                        break;
                    }
                }

                let remaining_needed = samples_needed - samples_filled;
                let available = self.buffer.len();
                let to_copy = std::cmp::min(remaining_needed, available);

                if to_copy > 0 {
                    let chunk: Vec<i16> = self.buffer.drain(0..to_copy).collect();
                    for (i, sample) in chunk.iter().enumerate() {
                        frames[samples_filled + i] = *sample;
                    }
                    samples_filled += to_copy;
                }
            }

            if samples_filled < samples_needed {
                for i in samples_filled..samples_needed { frames[i] = 0; }
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
        pub fn new(_transport: Box<dyn PacketTransport>) -> Self { Self }
        pub fn start_recording(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn stop_recording(&self) -> Result<(), AudioError> { Ok(()) }
        pub fn push_incoming_packet(&self, _data: Vec<u8>) {}
        pub fn shutdown(&self) {}
    }
    #[uniffi::export]
    pub fn init_logger() {}
}

#[cfg(target_os = "android")]
pub use real_impl::{AudioEngine, init_logger};
#[cfg(not(target_os = "android"))]
pub use stub_impl::{AudioEngine, init_logger};