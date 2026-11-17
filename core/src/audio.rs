pub mod opus;

// The sound the core hears and gives back, the same on every system and for every engine: one channel of 16-bit
// samples at 16 kHz, cut into frames of 20 ms.
pub const SAMPLE_RATE: usize = 16_000;
pub const FRAMES_PER_SECOND: usize = 50;
pub const FRAME_SAMPLES: usize = SAMPLE_RATE / FRAMES_PER_SECOND;

pub type Frame = [i16; FRAME_SAMPLES];
