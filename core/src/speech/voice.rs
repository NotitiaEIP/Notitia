use std::collections::VecDeque;

use crate::audio::{FRAME_SAMPLES, FRAMES_PER_SECOND, Frame};

// How far back the noise of the room is learned: long enough to hold pauses while someone talks on, short enough to
// follow a room that grows quiet. A frame is judged by these frames alone, so a reading taken up at any frame judges
// what follows as an unbroken one did.
pub const HEARD_FRAMES: usize = 5 * FRAMES_PER_SECOND;

// The noise is the level a tenth of the frames stay under: the pauses and the gaps between syllables, in a quiet room
// as in a loud one. Learned from every frame, not only from those judged quiet: a noise above the first guess would
// never be learned (#155).
const NOISE_RANK: usize = 10;

// Below it, a frame is the hiss of a quiet room however quiet the room: RMS 300 of 32,768, -41 dBFS.
const QUIETEST: u64 = 300 * 300 * FRAME_SAMPLES as u64;

// Speech stands 2.5 times above the noise, 8 dB: 6.25 times its energy.
const ABOVE_NOISE: (u128, u128) = (25, 4);

// Tells speech from the noise of the room, frame by frame. In integers alone: two readings of the same sound judge it
// the same on every system.
#[derive(Default)]
pub struct Voice {
    heard: VecDeque<u64>,
    sorted: Vec<u64>,
}

impl Voice {
    // Whether the frame, the next one of the meeting, is speech.
    pub fn hear(&mut self, frame: &Frame) -> bool {
        let energy = energy(frame);
        if self.heard.len() == HEARD_FRAMES
            && let Some(oldest) = self.heard.pop_front()
            && let Ok(at) = self.sorted.binary_search(&oldest)
        {
            self.sorted.remove(at);
        }
        self.heard.push_back(energy);
        self.sorted.insert(self.sorted.partition_point(|heard| *heard < energy), energy);
        let noise = self.sorted.get(self.sorted.len() / NOISE_RANK).copied().unwrap_or(0);
        let (times, over) = ABOVE_NOISE;
        energy >= QUIETEST && u128::from(energy) * over >= u128::from(noise) * times
    }
}

// The sum of the squares of its samples: at most 320 × 32,768², far from the end of a u64.
fn energy(frame: &Frame) -> u64 {
    frame.iter().map(|sample| i64::from(*sample).pow(2).unsigned_abs()).sum()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::speech::testing::{added, frames, hum, noise, quiet, talk};

    fn judged(voice: &mut Voice, sound: &[i16]) -> Vec<bool> {
        frames(sound).iter().map(|frame| voice.hear(frame)).collect()
    }

    fn share(judged: &[bool]) -> f64 {
        judged.iter().filter(|speech| **speech).count() as f64 / judged.len() as f64
    }

    #[test]
    fn silence_is_not_speech() {
        assert!(!judged(&mut Voice::default(), &quiet(5_000)).contains(&true));
    }

    #[test]
    fn the_noise_of_a_quiet_room_is_not_speech() {
        assert!(!judged(&mut Voice::default(), &noise(10_000, 250)).contains(&true));
    }

    #[test]
    fn a_voice_in_a_quiet_room_is_speech() {
        let mut voice = Voice::default();
        judged(&mut voice, &quiet(2_000));
        assert!(share(&judged(&mut voice, &talk(4_000))) > 0.5);
    }

    #[test]
    fn a_loud_noise_from_the_first_frame_is_learned_as_noise() {
        let mut voice = Voice::default();
        assert!(!judged(&mut voice, &noise(3_000, 400)).contains(&true));
        // The pauses of a meeting in that room are noise again, its words speech.
        for _ in 0..4 {
            assert!(share(&judged(&mut voice, &added(&talk(3_000), &noise(3_000, 400)))) > 0.5);
            assert!(!judged(&mut voice, &noise(1_000, 400)).contains(&true));
        }
    }

    #[test]
    fn a_steady_hum_as_loud_as_a_voice_is_not_speech() {
        assert!(!judged(&mut Voice::default(), &hum(10_000, 5_000.0)).contains(&true));
    }

    #[test]
    fn a_room_that_grows_quiet_is_followed() {
        let mut voice = Voice::default();
        judged(&mut voice, &noise(20_000, 2_000));
        judged(&mut voice, &quiet(1_000));
        assert!(share(&judged(&mut voice, &talk(2_000))) > 0.5);
    }

    #[test]
    fn a_frame_is_judged_by_the_last_five_seconds_alone() {
        let before = [quiet(3_000), talk(7_000), noise(4_000, 800), added(&talk(9_000), &noise(9_000, 800))].concat();
        let after = [talk(2_000), noise(3_000, 300), talk(4_000)].concat();
        let mut unbroken = Voice::default();
        judged(&mut unbroken, &before);
        let mut taken_up = Voice::default();
        judged(&mut taken_up, &before[before.len() - HEARD_FRAMES * FRAME_SAMPLES..]);
        assert_eq!(judged(&mut unbroken, &after), judged(&mut taken_up, &after));
    }
}
