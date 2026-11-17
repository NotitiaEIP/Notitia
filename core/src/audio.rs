pub mod opus;
pub mod sealed;

use std::{fmt, io};

use crate::secret::SecretError;

// The sound the core hears and gives back, the same on every system and for every engine: one channel of 16-bit
// samples at 16 kHz, cut into frames of 20 ms.
pub const SAMPLE_RATE: usize = 16_000;
pub const FRAMES_PER_SECOND: usize = 50;
pub const FRAME_SAMPLES: usize = SAMPLE_RATE / FRAMES_PER_SECOND;

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
