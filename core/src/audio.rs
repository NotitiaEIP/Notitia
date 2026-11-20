pub mod opus;
pub mod sealed;

use std::fs::File;
use std::{fmt, io};

use crate::secret::{Key, SecretError};
use opus::{Decoder, Encoder, Packet};

// The sound the core hears and gives back, the same on every system and for every engine: one channel of 16-bit
// samples at 16 kHz, cut into frames of 20 ms.
pub const SAMPLE_RATE: usize = 16_000;
pub const FRAMES_PER_SECOND: usize = 50;
pub const FRAME_SAMPLES: usize = SAMPLE_RATE / FRAMES_PER_SECOND;
pub const FRAME_MS: u64 = 1_000 / FRAMES_PER_SECOND as u64;

pub type Frame = [i16; FRAME_SAMPLES];

// Says nothing of the sound: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq)]
pub enum AudioError {
    // The meeting has no audio: never recorded, or deleted since.
    Absent,
    // No key, another key, or a file changed since it was sealed. A seal cannot tell which.
    Locked,
    // Written by a newer app, or not an audio at all: left as it is.
    Unsupported,
    // The disk, or a system that gives no random bytes.
    Unreadable,
    Codec,
    // Not the tracks the file has.
    Tracks,
    // Its last second is written: nothing is added after it.
    Ended,
}

impl fmt::Display for AudioError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            AudioError::Absent => "the meeting has no audio",
            AudioError::Locked => "the audio does not open with this key",
            AudioError::Unsupported => "the audio was written by a newer app",
            AudioError::Unreadable => "the audio cannot be read or written",
            AudioError::Codec => "the sound could not be encoded or decoded",
            AudioError::Tracks => "the sound does not have the tracks of the audio",
            AudioError::Ended => "the audio has ended",
        })
    }
}

impl std::error::Error for AudioError {}

impl From<io::Error> for AudioError {
    fn from(_: io::Error) -> Self {
        AudioError::Unreadable
    }
}

impl From<SecretError> for AudioError {
    fn from(error: SecretError) -> Self {
        match error {
            SecretError::Forged | SecretError::KeySize => AudioError::Locked,
            SecretError::NoRandom => AudioError::Unreadable,
        }
    }
}

// The audio of a meeting as it is recorded: each frame is encoded when it is heard and each second sealed when it
// is full, so no more than a second is ever held in memory.
pub struct Writer {
    sealed: sealed::Writer,
    encoders: Vec<Encoder>,
    second: Vec<Packet>,
}

impl Writer {
    // The file is opened to read and to write. One that holds an audio already is taken up where it stopped.
    pub fn open(file: File, key: &Key, tracks: usize) -> Result<Self, AudioError> {
        let sealed = sealed::Writer::open(file, key, tracks)?;
        let encoders = (0..tracks).map(|_| Encoder::new()).collect::<Result<_, _>>()?;
        Ok(Self { sealed, encoders, second: Vec::new() })
    }

    // The frames heard at one moment, one a track: the tracks of a file then always have the same length. Two
    // sources that do not run at the same pace are paired by who records them.
    pub fn write(&mut self, moment: &[Frame]) -> Result<(), AudioError> {
        if moment.len() != self.encoders.len() {
            return Err(AudioError::Tracks);
        }
        // A second the disk refused is tried again before anything is added to it.
        self.seal_when_full()?;
        let packets = self.encoders.iter_mut().zip(moment).map(|(encoder, frame)| encoder.encode(frame));
        self.second.extend(packets.collect::<Result<Vec<_>, _>>()?);
        self.seal_when_full()
    }

    // Every frame given, those not sealed yet included: what the audio holds once finished.
    pub fn frames(&self) -> u64 {
        self.sealed.frames() + (self.second.len() / self.encoders.len()) as u64
    }

    // The last second, short as it may be. Nothing is written after it.
    pub fn finish(mut self) -> Result<(), AudioError> {
        if !self.second.is_empty() {
            self.sealed.append(&self.second)?;
        }
        self.sealed.flush()
    }

    fn seal_when_full(&mut self) -> Result<(), AudioError> {
        if self.second.len() == FRAMES_PER_SECOND * self.encoders.len() {
            self.sealed.append(&self.second)?;
            self.second.clear();
        }
        Ok(())
    }
}

// What a decoder must hear before a passage to give it back as it was recorded (RFC 7845): 80 ms.
const WARM_UP: u64 = 4;
const SAMPLES_PER_MS: u64 = SAMPLE_RATE as u64 / 1_000;

pub struct Reader {
    sealed: sealed::Reader,
}

impl Reader {
    pub fn open(file: File, key: &Key) -> Result<Self, AudioError> {
        sealed::Reader::open(file, key).map(|sealed| Self { sealed })
    }

    pub fn tracks(&self) -> usize {
        self.sealed.tracks()
    }

    pub fn duration_ms(&self) -> u64 {
        self.sealed.frames() * FRAME_MS
    }

    // The samples of a passage, track by track, shorter than asked where the audio ends. Only the seconds of the
    // passage are opened, and the one before it when the decoder warms up there. A sound comes back 6.5 ms later
    // than it was heard, the delay of the encoder: nothing a player or a transcript can tell.
    pub fn read(&mut self, from_ms: u64, length_ms: u64) -> Result<Vec<Vec<i16>>, AudioError> {
        let tracks = self.tracks();
        let until_ms = from_ms.saturating_add(length_ms).min(self.duration_ms());
        let (first, end) = (from_ms / FRAME_MS, until_ms.div_ceil(FRAME_MS));
        let mut decoders = (0..tracks).map(|_| Decoder::new()).collect::<Result<Vec<_>, _>>()?;
        let mut heard = vec![Vec::new(); tracks];
        let mut second = (u64::MAX, Vec::new());
        for frame in first.saturating_sub(WARM_UP)..end {
            let (number, within) = (frame / FRAMES_PER_SECOND as u64, (frame % FRAMES_PER_SECOND as u64) as usize);
            if second.0 != number {
                second = (number, self.sealed.second(number)?);
            }
            let packets = second.1.get(within * tracks..(within + 1) * tracks).ok_or(AudioError::Unsupported)?;
            for ((decoder, packet), heard) in decoders.iter_mut().zip(packets).zip(&mut heard) {
                let sound = decoder.decode(packet)?;
                if frame >= first {
                    heard.extend(sound);
                }
            }
        }
        // A passage starts and ends where it was asked, not where a frame does.
        let skipped = from_ms.saturating_sub(first * FRAME_MS) * SAMPLES_PER_MS;
        let asked = until_ms.saturating_sub(from_ms) * SAMPLES_PER_MS;
        for heard in &mut heard {
            heard.drain(..(skipped as usize).min(heard.len()));
            heard.truncate(asked as usize);
        }
        Ok(heard)
    }
}

impl From<opus::CodecError> for AudioError {
    fn from(_: opus::CodecError) -> Self {
        AudioError::Codec
    }
}

#[cfg(test)]
pub(crate) mod testing {
    use std::f64::consts::TAU;

    use super::*;

    // The witness of version 1 of the format: 2.5 seconds heard on two tracks, `chord` on the first and an 880 Hz note
    // on the second, sealed by key(1). The core of any later app reads it as it was heard.
    pub const WITNESS: &[u8] = include_bytes!("../tests/fixtures/audio-v1.audio");

    // Made up, like every sound of the tests: notes that swell and fade four times a second.
    pub fn notes(notes: &[f64], frames: usize) -> Vec<Frame> {
        let sample = |at: usize| {
            let time = at as f64 / SAMPLE_RATE as f64;
            let sound: f64 = notes.iter().map(|note| (TAU * note * time).sin()).sum();
            (sound * (TAU * 2.0 * time).sin().abs() * 5_000.0) as i16
        };
        (0..frames).map(|frame| std::array::from_fn(|at| sample(frame * FRAME_SAMPLES + at))).collect()
    }

    pub fn chord(frames: usize) -> Vec<Frame> {
        notes(&[220.0, 330.0, 550.0], frames)
    }

    // 1 for the same sound, 0 for two sounds with nothing in common.
    pub fn likeness(heard: &[i16], read: &[i16]) -> f64 {
        let dot = |a: &[i16], b: &[i16]| a.iter().zip(b).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum::<f64>();
        dot(heard, read) / (dot(heard, heard) * dot(read, read)).sqrt()
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{self, OpenOptions};

    use tempfile::TempDir;

    use super::testing::{WITNESS, chord, likeness, notes};
    use super::*;
    use crate::secret::testing::key;

    fn file(folder: &TempDir) -> File {
        let path = folder.path().join("recorded-1.audio");
        OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path).unwrap()
    }

    // Each track is given its own sound, frame by frame.
    fn recorded(tracks: &[Vec<Frame>]) -> (TempDir, Reader) {
        let folder = tempfile::tempdir().unwrap();
        let mut writer = Writer::open(file(&folder), &key(1), tracks.len()).unwrap();
        for frame in 0..tracks[0].len() {
            writer.write(&tracks.iter().map(|track| track[frame]).collect::<Vec<_>>()).unwrap();
        }
        writer.finish().unwrap();
        let reader = Reader::open(file(&folder), &key(1)).unwrap();
        (folder, reader)
    }

    // How close what is read is to what was heard, once the delay of the encoder is taken off: 104 samples, give
    // or take the two its filters add.
    fn alike(heard: &[Frame], read: &[i16]) -> f64 {
        let heard = heard.concat();
        (102..=106).map(|late| likeness(&heard[..read.len() - late], &read[late..])).fold(-1.0, f64::max)
    }

    #[test]
    fn a_meeting_is_heard_again_to_its_last_frame() {
        let heard = chord(115);
        let (_folder, mut reader) = recorded(std::slice::from_ref(&heard));
        assert_eq!((reader.tracks(), reader.duration_ms()), (1, 2_300));
        let read = reader.read(0, 10_000).unwrap();
        assert_eq!(read[0].len(), 115 * FRAME_SAMPLES);
        assert!(alike(&heard, &read[0]) > 0.95);
    }

    #[test]
    fn a_passage_sounds_as_it_does_in_the_whole_meeting() {
        let (_folder, mut reader) = recorded(&[chord(300)]);
        let whole = reader.read(0, 6_000).unwrap().remove(0);
        // From the middle of a frame, across two seconds, to the middle of another.
        let passage = reader.read(3_730, 1_505).unwrap().remove(0);
        let (from, length) = (3_730 * SAMPLES_PER_MS as usize, 1_505 * SAMPLES_PER_MS as usize);
        assert_eq!(passage.len(), length);
        assert!(likeness(&whole[from..from + length], &passage) > 0.999);
    }

    #[test]
    fn two_tracks_keep_each_its_own_sound() {
        let (voice, visio) = (chord(60), notes(&[880.0], 60));
        let (_folder, mut reader) = recorded(&[voice.clone(), visio.clone()]);
        let read = reader.read(0, 1_200).unwrap();
        assert_eq!(read.len(), 2);
        assert!(alike(&voice, &read[0]) > 0.95 && alike(&visio, &read[1]) > 0.95);
        assert!(alike(&voice, &read[1]).abs() < 0.2);
    }

    #[test]
    fn nothing_is_read_past_the_end() {
        let (_folder, mut reader) = recorded(&[chord(60)]);
        assert_eq!(reader.read(1_000, 5_000).unwrap()[0].len(), 200 * SAMPLES_PER_MS as usize);
        assert!(reader.read(1_200, 100).unwrap()[0].is_empty());
        assert!(reader.read(u64::MAX, u64::MAX).unwrap()[0].is_empty());
    }

    #[test]
    fn a_recording_is_taken_up_where_a_crash_left_it() {
        let folder = tempfile::tempdir().unwrap();
        let heard = chord(150);
        let mut writer = Writer::open(file(&folder), &key(1), 1).unwrap();
        heard[..120].iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        // Killed: the 20 frames of the third second were only in memory.
        drop(writer);
        let mut writer = Writer::open(file(&folder), &key(1), 1).unwrap();
        assert_eq!(writer.frames(), 100);
        heard[100..].iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        assert_eq!(writer.frames(), 150);
        writer.finish().unwrap();
        let mut reader = Reader::open(file(&folder), &key(1)).unwrap();
        assert_eq!(reader.duration_ms(), 3_000);
        assert!(alike(&heard, &reader.read(0, 3_000).unwrap()[0]) > 0.9);
    }

    #[test]
    fn the_witness_of_version_1_is_heard_as_it_was_recorded() {
        let folder = tempfile::tempdir().unwrap();
        // A copy: no test can change the witness.
        fs::write(folder.path().join("recorded-1.audio"), WITNESS).unwrap();
        let mut reader = Reader::open(file(&folder), &key(1)).unwrap();
        assert_eq!((reader.tracks(), reader.duration_ms()), (2, 2_500));
        let read = reader.read(0, 2_500).unwrap();
        assert!(alike(&chord(125), &read[0]) > 0.95 && alike(&notes(&[880.0], 125), &read[1]) > 0.95);
    }

    #[test]
    fn a_moment_has_a_frame_for_each_track() {
        let folder = tempfile::tempdir().unwrap();
        let mut writer = Writer::open(file(&folder), &key(1), 2).unwrap();
        assert_eq!(writer.write(&chord(1)), Err(AudioError::Tracks));
        assert_eq!(writer.write(&chord(3)), Err(AudioError::Tracks));
        writer.finish().unwrap();
        assert_eq!(Reader::open(file(&folder), &key(1)).unwrap().duration_ms(), 0);
    }
}
