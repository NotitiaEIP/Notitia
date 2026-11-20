use std::fmt;
use std::panic::{self, AssertUnwindSafe};

use opus_rs::{Application, OpusDecoder, OpusEncoder};

use super::{FRAME_SAMPLES, FRAMES_PER_SECOND, Frame, SAMPLE_RATE};

// Opus (RFC 6716), and the only file that knows which library speaks it: docs/adr/0005-audio.md says why this one.
// A packet is standard Opus, which any other library reads back.

// Constant, so every packet has one size: a second of a meeting then sits at a place known without reading what
// comes before it, and its size says nothing of who spoke.
const BITRATE: usize = 24_000;
pub const PACKET_BYTES: usize = BITRATE / 8 / FRAMES_PER_SECOND;

pub type Packet = [u8; PACKET_BYTES];

// Half the work of the highest setting, for a sound that no measure of the ADR tells apart.
const COMPLEXITY: i32 = 5;

// Says nothing of the sound: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq)]
pub struct CodecError;

impl fmt::Display for CodecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("the sound could not be encoded or decoded")
    }
}

impl std::error::Error for CodecError {}

// The library is young: should it panic, a recording stops on a failure and the app does not fall with it.
fn guarded<T>(work: impl FnOnce() -> Result<T, &'static str>) -> Result<T, CodecError> {
    panic::catch_unwind(AssertUnwindSafe(work)).map_err(|_| CodecError)?.map_err(|_| CodecError)
}

pub struct Encoder(OpusEncoder);

impl Encoder {
    pub fn new() -> Result<Self, CodecError> {
        let mut encoder = guarded(|| OpusEncoder::new(SAMPLE_RATE as i32, 1, Application::Voip))?;
        encoder.bitrate_bps = BITRATE as i32;
        encoder.use_cbr = true;
        encoder.complexity = COMPLEXITY;
        Ok(Self(encoder))
    }

    // How many samples late a sound comes back: a file made for another player has to say it.
    pub fn delay(&self) -> usize {
        self.0.lookahead()
    }

    pub fn encode(&mut self, frame: &Frame) -> Result<Packet, CodecError> {
        let mut packet = [0; PACKET_BYTES];
        match guarded(|| self.0.encode_i16(frame, FRAME_SAMPLES, &mut packet)) {
            Ok(PACKET_BYTES) => Ok(packet),
            _ => {
                // Nothing says where a failure left it: the next frame starts from a new one.
                *self = Self::new()?;
                Err(CodecError)
            }
        }
    }
}

pub struct Decoder(OpusDecoder);

impl Decoder {
    pub fn new() -> Result<Self, CodecError> {
        guarded(|| OpusDecoder::new(SAMPLE_RATE as i32, 1)).map(Self)
    }

    pub fn decode(&mut self, packet: &Packet) -> Result<Frame, CodecError> {
        let mut sound = [0.0; FRAME_SAMPLES];
        match guarded(|| self.0.decode(packet, FRAME_SAMPLES, &mut sound)) {
            Ok(FRAME_SAMPLES) => Ok(sound.map(|sample| (sample * 32_768.0).round() as i16)),
            _ => {
                *self = Self::new()?;
                Err(CodecError)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio::testing::{chord, likeness};
    use crate::testing::each_damaged;

    // Made up, like every sound of these tests.
    fn noise(frames: usize) -> Vec<Frame> {
        let mut state = 1u64;
        let mut sample = || {
            state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            (state >> 48) as i16
        };
        (0..frames).map(|_| std::array::from_fn(|_| sample())).collect()
    }

    fn there_and_back(heard: &[Frame]) -> (Vec<i16>, usize) {
        let (mut encoder, mut decoder) = (Encoder::new().unwrap(), Decoder::new().unwrap());
        let read = heard.iter().flat_map(|frame| decoder.decode(&encoder.encode(frame).unwrap()).unwrap()).collect();
        (read, encoder.delay())
    }

    #[test]
    fn a_sound_comes_back_as_it_went_in() {
        let heard = chord(100);
        let (read, delay) = there_and_back(&heard);
        let heard = heard.concat();
        // Late by the delay the encoder tells, give or take the two samples its filters add. Close to what went in
        // without being it: Opus keeps what an ear hears, not each sample.
        let alike = (delay - 2..=delay + 2).map(|late| likeness(&heard[..read.len() - late], &read[late..]));
        assert!(alike.fold(0.0, f64::max) > 0.95);
    }

    #[test]
    fn silence_and_the_loudest_noise_are_sounds_too() {
        let (read, _) = there_and_back(&[[0; FRAME_SAMPLES]; 50]);
        assert!(read.iter().all(|sample| sample.abs() < 10));
        let (read, _) = there_and_back(&noise(50));
        assert!(read.iter().any(|sample| sample.abs() > 10_000));
    }

    #[test]
    fn a_packet_that_is_no_sound_is_refused_and_the_next_one_is_read() {
        let (mut encoder, mut decoder) = (Encoder::new().unwrap(), Decoder::new().unwrap());
        // Its first byte announces two frames of one size, which the 59 bytes left cannot be.
        assert_eq!(decoder.decode(&[0b0100_1001; PACKET_BYTES]), Err(CodecError));
        assert!(decoder.decode(&encoder.encode(&chord(1)[0]).unwrap()).is_ok());
    }

    // The library is young: each version it moves to reads these again, and must neither hang nor fall.
    #[test]
    fn a_damaged_or_drawn_packet_is_refused_or_decoded() {
        let mut encoder = Encoder::new().unwrap();
        let packets: Vec<Packet> = chord(50).iter().map(|frame| encoder.encode(frame).unwrap()).collect();
        let good: Vec<&[u8]> = packets.iter().map(|packet| packet.as_slice()).collect();
        // One decoder for all of them, as a meeting is read: what it refused never stops the next packet.
        let mut decoder = Decoder::new().unwrap();
        each_damaged(&good, 3_000, |bytes| {
            let mut packet = [0; PACKET_BYTES];
            let length = bytes.len().min(PACKET_BYTES);
            packet[..length].copy_from_slice(&bytes[..length]);
            let _ = decoder.decode(&packet);
        });
        assert!(decoder.decode(&packets[0]).is_ok());
    }

    #[test]
    fn a_library_that_panics_fails_and_the_core_stands() {
        assert_eq!(guarded::<()>(|| panic!("made up")), Err(CodecError));
    }
}
