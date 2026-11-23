// The audio of a meeting cut into windows a speech engine takes, and what the engine heard made into lines of the
// transcript. docs/adr/0011-fenetres.md.

pub mod voice;
pub mod windows;

pub use windows::{Next, Window, Windows};

// Made up, like every sound of the tests: no meeting was recorded for them.
#[cfg(test)]
pub(crate) mod testing {
    use std::f64::consts::TAU;

    use crate::audio::testing::chord;
    use crate::audio::{FRAME_MS, FRAME_SAMPLES, Frame, SAMPLES_PER_MS, mix};
    use crate::testing::Draw;

    pub fn samples(ms: u64) -> usize {
        ms as usize * SAMPLES_PER_MS
    }

    pub fn frames(sound: &[i16]) -> Vec<Frame> {
        sound.as_chunks::<FRAME_SAMPLES>().0.to_vec()
    }

    // Someone near the phone: syllables that swell and fade four times a second.
    pub fn talk(ms: u64) -> Vec<i16> {
        chord((ms / FRAME_MS) as usize).concat()
    }

    pub fn quiet(ms: u64) -> Vec<i16> {
        vec![0; samples(ms)]
    }

    // A room: the same white noise at every call, of the given RMS.
    pub fn noise(ms: u64, rms: i64) -> Vec<i16> {
        let mut draw = Draw(0x006e_6f69_7365);
        let peak = rms * 1_732 / 1_000;
        (0..samples(ms))
            .map(|_| (draw.next() % (2 * peak as u64 + 1)) as i64 - peak)
            .map(|sample| sample as i16)
            .collect()
    }

    // A fan or a hum: a low note that never changes, two periods a frame.
    pub fn hum(ms: u64, loudness: f64) -> Vec<i16> {
        let sample = |at: usize| ((TAU * 100.0 * at as f64 / 16_000.0).sin() * loudness) as i16;
        (0..samples(ms)).map(sample).collect()
    }

    pub fn added(sound: &[i16], other: &[i16]) -> Vec<i16> {
        mix(vec![sound.to_vec(), other.to_vec()])
    }
}
