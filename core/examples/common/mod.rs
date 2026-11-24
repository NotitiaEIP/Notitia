// The made-up hour the measures of the core hear, sealed as a recording leaves it on the disk.

use std::error::Error;
use std::f64::consts::TAU;
use std::fs::File;

use notitia::audio::opus::Encoder;
use notitia::audio::{FRAME_SAMPLES, FRAMES_PER_SECOND, Frame, SAMPLE_RATE, sealed};
use notitia::secret::Key;

// No meeting was recorded for this: three minutes of every kind of moment, heard twenty times over.
const PATTERN_SECONDS: usize = 180;
const HOUR_SECONDS: usize = 3_600;

// A voice as a machine draws one: a pitch that wanders, a few harmonics, four syllables a second.
fn talk(sound: &mut Vec<i16>, seconds: f64) {
    let start = sound.len();
    sound.extend((0..(seconds * SAMPLE_RATE as f64) as usize).map(|at| {
        let time = (start + at) as f64 / SAMPLE_RATE as f64;
        let pitch = 130.0 + 30.0 * (TAU * 0.7 * time).sin();
        let voiced: f64 =
            (1..=6).map(|harmonic| (TAU * pitch * f64::from(harmonic) * time).sin() / f64::from(harmonic)).sum();
        (voiced * (TAU * 2.0 * time).sin().abs().powf(0.6) * 6_000.0) as i16
    }));
}

fn quiet(sound: &mut Vec<i16>, seconds: f64) {
    sound.extend(std::iter::repeat_n(0, (seconds * SAMPLE_RATE as f64) as usize));
}

// A room: white noise of the given RMS added to what is there since `from`.
fn room(sound: &mut [i16], from: usize, rms: i64) {
    let mut state: u64 = 0x006e_6f69_7365;
    let peak = rms * 1_732 / 1_000;
    for sample in &mut sound[from..] {
        state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        let noise = ((state >> 33) % (2 * peak as u64 + 1)) as i64 - peak;
        *sample = sample.saturating_add(noise as i16);
    }
}

fn pattern() -> Vec<Frame> {
    let mut sound = Vec::new();
    for _ in 0..4 {
        talk(&mut sound, 5.0);
        quiet(&mut sound, 0.8);
    }
    quiet(&mut sound, 3.0);
    // Someone who never stops: cut in the middle of speech.
    talk(&mut sound, 45.0);
    quiet(&mut sound, 20.0);
    let noisy = sound.len();
    for _ in 0..8 {
        talk(&mut sound, 4.0);
        quiet(&mut sound, 1.2);
    }
    quiet(&mut sound, 10.0);
    room(&mut sound, noisy, 400);
    for _ in 0..5 {
        talk(&mut sound, 6.0);
        quiet(&mut sound, 0.4);
    }
    sound.resize(PATTERN_SECONDS * SAMPLE_RATE, 0);
    sound.as_chunks::<FRAME_SAMPLES>().0.to_vec()
}

// Into `file`, opened to read and to write. Encoded once, sealed twenty times: the hour is ready in seconds, not the
// minute an encoding takes.
pub fn seal_hour(file: File, key: &Key) -> Result<(), Box<dyn Error>> {
    let mut encoder = Encoder::new()?;
    let packets = pattern().iter().map(|frame| encoder.encode(frame)).collect::<Result<Vec<_>, _>>()?;
    let mut writer = sealed::Writer::open(file, key, 1)?;
    for second in packets.chunks(FRAMES_PER_SECOND).cycle().take(HOUR_SECONDS) {
        writer.append(second)?;
    }
    writer.flush()?;
    Ok(())
}
