// What an hour of sound costs to encode and to decode, and the room it takes (T-79). Optimised as an app is:
// cargo run --release --example recording
// A file given after `--` receives a minute of that sound as Ogg Opus, for ffmpeg or any player: the proof that what
// the core keeps is standard Opus.
use std::error::Error;
use std::f64::consts::TAU;
use std::time::Instant;
use std::{env, fs};

use notitia_core::audio::opus::{CodecError, Decoder, Encoder, PACKET_BYTES, Packet};
use notitia_core::audio::{FRAME_SAMPLES, FRAMES_PER_SECOND, Frame, SAMPLE_RATE};

const HOUR: usize = 3_600 * FRAMES_PER_SECOND;
const WITNESS: usize = 60 * FRAMES_PER_SECOND;
// Five seconds of voice and one of silence, heard again and again.
const SPOKEN: usize = 6 * FRAMES_PER_SECOND;

// Made up: no meeting was recorded for this. A voice as a machine draws one: a pitch that wanders, the three
// resonances of a vowel, four syllables a second.
fn voice() -> Vec<Frame> {
    let resonance = |pitch: f64| {
        let vowel = [(700.0, 130.0), (1_200.0, 170.0), (2_600.0, 250.0)];
        vowel.iter().map(|(centre, width)| 1.0 / (1.0 + ((pitch - centre) / width).powi(2))).sum::<f64>()
    };
    let mut phase = 0.0;
    let sample = |at: usize| {
        let time = at as f64 / SAMPLE_RATE as f64;
        let pitch = 130.0 + 30.0 * (TAU * 0.7 * time).sin();
        phase += pitch / SAMPLE_RATE as f64;
        let harmonics = (1..).map(f64::from).take_while(|harmonic| harmonic * pitch < 7_000.0);
        let vowel: f64 = harmonics.map(|harmonic| resonance(harmonic * pitch) * (TAU * harmonic * phase).sin()).sum();
        if time < 5.0 { vowel * (TAU * 2.0 * time).sin().abs().powf(0.6) } else { 0.0 }
    };
    let drawn: Vec<f64> = (0..SPOKEN * FRAME_SAMPLES).map(sample).collect();
    let loudest = drawn.iter().fold(0.0, |loudest: f64, sample| loudest.max(sample.abs()));
    // A third of full scale: someone speaking close to the phone.
    let (frames, _) = drawn.as_chunks::<FRAME_SAMPLES>();
    frames.iter().map(|frame| frame.map(|sample| (sample / loudest * 10_000.0) as i16)).collect()
}

// Ogg as RFC 7845 wraps Opus for a player. Here and not in the core, whose own file is sealed.
#[derive(Default)]
struct Ogg {
    bytes: Vec<u8>,
    pages: u32,
}

const FIRST: u8 = 2;
const LAST: u8 = 4;
// A player counts Opus at 48 kHz, whatever went in.
const PLAYED: usize = 48_000 / SAMPLE_RATE;

impl Ogg {
    // One length a packet is enough: none here reaches 255 bytes, and no page holds more than 255 of them.
    fn page(&mut self, kind: u8, played: usize, packets: &[&[u8]]) {
        let start = self.bytes.len();
        self.bytes.extend(b"OggS\0");
        self.bytes.push(kind);
        self.bytes.extend((played as u64).to_le_bytes());
        self.bytes.extend(1u32.to_le_bytes());
        self.bytes.extend(self.pages.to_le_bytes());
        // The place of the checksum, which covers the whole page with zeros there.
        self.bytes.extend([0; 4]);
        self.bytes.push(packets.len() as u8);
        self.bytes.extend(packets.iter().map(|packet| packet.len() as u8));
        self.bytes.extend(packets.concat());
        let checksum = self.bytes[start..].iter().fold(0, checksum);
        self.bytes[start + 22..start + 26].copy_from_slice(&checksum.to_le_bytes());
        self.pages += 1;
    }
}

// The CRC-32 of Ogg: polynomial 0x04C11DB7, the high bit first.
fn checksum(sum: u32, byte: &u8) -> u32 {
    let step = |sum: u32, _| if sum >> 31 == 0 { sum << 1 } else { (sum << 1) ^ 0x04C1_1DB7 };
    (0..8).fold(sum ^ (u32::from(*byte) << 24), step)
}

fn witness(spoken: &[Frame]) -> Result<Vec<u8>, CodecError> {
    let mut encoder = Encoder::new()?;
    // One frame of silence more, to push the end of the sound out of the encoder.
    let heard = spoken.iter().cycle().take(WITNESS).chain(&[[0; FRAME_SAMPLES]]);
    let packets = heard.map(|frame| encoder.encode(frame)).collect::<Result<Vec<Packet>, _>>()?;
    let late = encoder.delay() * PLAYED;
    let (late_bytes, rate_bytes) = ((late as u16).to_le_bytes(), (SAMPLE_RATE as u32).to_le_bytes());
    let head = [b"OpusHead\x01\x01".as_slice(), &late_bytes, &rate_bytes, &[0; 3]].concat();
    let tags = [b"OpusTags".as_slice(), &7u32.to_le_bytes(), b"Notitia", &0u32.to_le_bytes()].concat();
    let mut ogg = Ogg::default();
    ogg.page(FIRST, 0, &[&head]);
    ogg.page(0, 0, &[&tags]);
    let seconds = packets.chunks(FRAMES_PER_SECOND);
    // The last page says where the sound ends: the player drops what the frame of silence added.
    let (last, end) = (seconds.len() - 1, late + WITNESS * FRAME_SAMPLES * PLAYED);
    for (second, packets) in seconds.enumerate() {
        let (kind, played) = if second == last { (LAST, end) } else { (0, (second + 1) * SAMPLE_RATE * PLAYED) };
        ogg.page(kind, played, &packets.iter().map(Packet::as_slice).collect::<Vec<_>>());
    }
    Ok(ogg.bytes)
}

fn main() -> Result<(), Box<dyn Error>> {
    let spoken = voice();
    let (mut encoder, mut decoder) = (Encoder::new()?, Decoder::new()?);
    let started = Instant::now();
    let packets = spoken.iter().cycle().take(HOUR).map(|frame| encoder.encode(frame)).collect::<Result<Vec<_>, _>>()?;
    let encoded = started.elapsed().as_secs_f64();
    let started = Instant::now();
    for packet in &packets {
        decoder.decode(packet)?;
    }
    let decoded = started.elapsed().as_secs_f64();
    println!("an hour of sound: {:.1} MB", (packets.len() * PACKET_BYTES) as f64 / 1e6);
    println!("encoded in {encoded:>5.1} s, {:>4.0} times faster than it is heard", 3_600.0 / encoded);
    println!("decoded in {decoded:>5.1} s, {:>4.0} times faster than it is heard", 3_600.0 / decoded);
    if let Some(file) = env::args_os().nth(1) {
        fs::write(file, witness(&spoken)?)?;
    }
    Ok(())
}
