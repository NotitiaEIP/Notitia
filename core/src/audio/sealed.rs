use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};

use super::opus::{PACKET_BYTES, Packet};
use super::{AudioError, FRAMES_PER_SECOND};
use crate::secret::{Key, SALT_BYTES, SEAL_BYTES, Seal, random};

// The audio of a meeting on the disk, and the only file that knows where each byte goes (docs/adr/0005-audio.md):
// a header in clear, then one sealed record a second, all of one size. Second `n` is found by a multiplication,
// and read without opening what comes before it.

const MARK: &[u8; 8] = b"NTTAUDIO";
const VERSION: u8 = 1;
// The mark, the version, the tracks, the size of a packet, four bytes for later, the salt.
const HEADER_BYTES: usize = 48;
const SALT_AT: usize = HEADER_BYTES - SALT_BYTES;
// Told to the key: what seals the audio opens no other kind of file.
const PURPOSE: &str = "notitia audio 1";

const FLUSH_EVERY: u64 = 10;
// The most a power cut leaves at the end that the disk never received: the seconds since the last flush, twice
// over. More seconds than that which do not open is a wrong key or a changed file, and nothing is cut then: a
// wrong key must never empty a file.
const UNFLUSHED: u64 = 2 * FLUSH_EVERY;

struct Shape {
    header: [u8; HEADER_BYTES],
    tracks: usize,
    seal: Seal,
}

impl Shape {
    fn new(key: &Key, tracks: usize) -> Result<Self, AudioError> {
        let count = u8::try_from(tracks).ok().filter(|count| *count > 0).ok_or(AudioError::Tracks)?;
        let mut header = [0; HEADER_BYTES];
        header[..8].copy_from_slice(MARK);
        header[8] = VERSION;
        header[9] = count;
        header[10..12].copy_from_slice(&(PACKET_BYTES as u16).to_le_bytes());
        header[SALT_AT..].copy_from_slice(&random::<SALT_BYTES>()?);
        Self::read(key, header)
    }

    fn read(key: &Key, header: [u8; HEADER_BYTES]) -> Result<Self, AudioError> {
        let (tracks, packet) = (usize::from(header[9]), u16::from_le_bytes([header[10], header[11]]));
        // The version settles the rest: Opus, 16 kHz, frames of 20 ms. Another one is a newer app's.
        let known = &header[..8] == MARK && header[8] == VERSION && tracks > 0 && usize::from(packet) == PACKET_BYTES;
        let salt = <&[u8; SALT_BYTES]>::try_from(&header[SALT_AT..]).ok().filter(|_| known);
        let seal = key.derive(PURPOSE, salt.ok_or(AudioError::Unsupported)?)?;
        Ok(Self { header, tracks, seal })
    }

    fn of(file: &mut File, key: &Key) -> Result<Self, AudioError> {
        // Shorter than its header, a file was cut while it was made: no sound ever reached it.
        if file.metadata()?.len() < HEADER_BYTES as u64 {
            return Err(AudioError::Absent);
        }
        let mut header = [0; HEADER_BYTES];
        file.seek(SeekFrom::Start(0))?;
        file.read_exact(&mut header)?;
        Self::read(key, header)
    }

    fn record_bytes(&self) -> u64 {
        (SEAL_BYTES + 1 + self.tracks * FRAMES_PER_SECOND * PACKET_BYTES) as u64
    }

    fn place(&self, second: u64) -> u64 {
        HEADER_BYTES as u64 + second * self.record_bytes()
    }

    // Sealed with each second: one moved to another place, or to another file, is refused there.
    fn about(&self, second: u64) -> Vec<u8> {
        [self.header.as_slice(), &second.to_le_bytes()].concat()
    }

    // How many frames the second holds, then a packet a track for each frame, and zeros up to the size every
    // second has.
    fn sealed(&self, second: u64, packets: &[Packet]) -> Result<Vec<u8>, AudioError> {
        let frames = packets.len() / self.tracks;
        if packets.is_empty() || !packets.len().is_multiple_of(self.tracks) || frames > FRAMES_PER_SECOND {
            return Err(AudioError::Tracks);
        }
        let mut clear = vec![0; self.record_bytes() as usize - SEAL_BYTES];
        clear[0] = frames as u8;
        clear[1..=packets.len() * PACKET_BYTES].copy_from_slice(packets.as_flattened());
        Ok(self.seal.seal(&self.about(second), &clear)?)
    }

    fn opened(&self, second: u64, record: &[u8]) -> Result<Vec<Packet>, AudioError> {
        let clear = self.seal.open(&self.about(second), record)?;
        let (frames, packets) = clear.split_first().ok_or(AudioError::Locked)?;
        let (packets, _) = packets.as_chunks::<PACKET_BYTES>();
        let held = usize::from(*frames) * self.tracks;
        // Sealed by a key that fits, with a count no second has: a newer app's.
        if *frames == 0 || held > packets.len() { Err(AudioError::Unsupported) } else { Ok(packets[..held].to_vec()) }
    }

    fn second(&self, file: &mut File, second: u64) -> Result<Vec<Packet>, AudioError> {
        let mut record = vec![0; self.record_bytes() as usize];
        file.seek(SeekFrom::Start(self.place(second)))?;
        file.read_exact(&mut record)?;
        self.opened(second, &record)
    }

    // The seconds the file holds for sure, and the frames of the last one. What follows them was cut by a crash:
    // a second begun, or seconds the disk never received.
    fn kept(&self, file: &mut File) -> Result<(u64, usize), AudioError> {
        let written = file.metadata()?.len().saturating_sub(HEADER_BYTES as u64) / self.record_bytes();
        let checked = written.saturating_sub(UNFLUSHED + 1);
        for second in (checked..written).rev() {
            match self.second(file, second) {
                Ok(last) => return Ok((second + 1, last.len() / self.tracks)),
                Err(AudioError::Locked) => {}
                Err(other) => return Err(other),
            }
        }
        if written == 0 { Ok((0, FRAMES_PER_SECOND)) } else { Err(AudioError::Locked) }
    }
}

// Every second is whole but the last.
fn frames_in(seconds: u64, last: usize) -> u64 {
    seconds.checked_sub(1).map_or(0, |whole| whole * FRAMES_PER_SECOND as u64 + last as u64)
}

// Adds seconds to the audio of a meeting, and takes up a file an earlier launch left.
pub struct Writer {
    file: File,
    shape: Shape,
    seconds: u64,
    frames: u64,
    // A second shorter than the others closes the audio: one written after it would not be where its number says.
    ended: bool,
}

impl Writer {
    // The file is the caller's, opened to read and to write: the store names it and says who may read it.
    pub fn open(mut file: File, key: &Key, tracks: usize) -> Result<Self, AudioError> {
        // The header reaches the disk before any second: a file without one has no sound to lose.
        if file.metadata()?.len() < HEADER_BYTES as u64 {
            let shape = Shape::new(key, tracks)?;
            file.set_len(0)?;
            file.seek(SeekFrom::Start(0))?;
            file.write_all(&shape.header)?;
            file.sync_all()?;
            return Ok(Self { file, shape, seconds: 0, frames: 0, ended: false });
        }
        let shape = Shape::of(&mut file, key)?;
        if shape.tracks != tracks {
            return Err(AudioError::Tracks);
        }
        let (seconds, last) = shape.kept(&mut file)?;
        file.set_len(shape.place(seconds))?;
        Ok(Self { file, shape, seconds, frames: frames_in(seconds, last), ended: last < FRAMES_PER_SECOND })
    }

    pub fn seconds(&self) -> u64 {
        self.seconds
    }

    pub fn frames(&self) -> u64 {
        self.frames
    }

    pub fn tracks(&self) -> usize {
        self.shape.tracks
    }

    // A packet a track for each frame of the second, a frame after the other. Fewer than a second of them ends
    // the audio.
    pub fn append(&mut self, packets: &[Packet]) -> Result<(), AudioError> {
        if self.ended {
            return Err(AudioError::Ended);
        }
        let record = self.shape.sealed(self.seconds, packets)?;
        let end = self.shape.place(self.seconds);
        let written = self.file.seek(SeekFrom::Start(end)).and_then(|_| self.file.write_all(&record));
        if written.is_err() {
            // A full disk leaves half a second: taken back, so the next one lands where its number says.
            let _ = self.file.set_len(end);
        }
        written?;
        self.seconds += 1;
        self.frames += (packets.len() / self.shape.tracks) as u64;
        self.ended = packets.len() < FRAMES_PER_SECOND * self.shape.tracks;
        // A killed app loses nothing the system was given. Only a power cut does, and no more than these seconds.
        if self.ended || self.seconds.is_multiple_of(FLUSH_EVERY) { self.flush() } else { Ok(()) }
    }

    pub fn flush(&mut self) -> Result<(), AudioError> {
        Ok(self.file.sync_data()?)
    }
}

pub struct Reader {
    file: File,
    shape: Shape,
    seconds: u64,
    last: usize,
}

impl Reader {
    pub fn open(mut file: File, key: &Key) -> Result<Self, AudioError> {
        let shape = Shape::of(&mut file, key)?;
        let (seconds, last) = shape.kept(&mut file)?;
        Ok(Self { file, shape, seconds, last })
    }

    pub fn tracks(&self) -> usize {
        self.shape.tracks
    }

    pub fn frames(&self) -> u64 {
        frames_in(self.seconds, self.last)
    }

    // Nothing past the last second kept: what a crash cut is never read as a sound.
    pub fn second(&mut self, second: u64) -> Result<Vec<Packet>, AudioError> {
        if second < self.seconds { self.shape.second(&mut self.file, second) } else { Ok(Vec::new()) }
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{self, OpenOptions};
    use std::path::{Path, PathBuf};

    use tempfile::TempDir;

    use super::*;
    use crate::secret::testing::key;

    const ONE_TRACK: u64 = 3_041;
    const TWO_TRACKS: u64 = 6_041;
    const HEADER: u64 = HEADER_BYTES as u64;

    // Made up, and no sound at all: what a second holds is not this file's concern.
    fn packets(second: u8, count: usize) -> Vec<Packet> {
        (0..count).map(|at| std::array::from_fn(|byte| second ^ (at as u8).wrapping_mul(31) ^ byte as u8)).collect()
    }

    fn second(number: u8) -> Vec<Packet> {
        packets(number, FRAMES_PER_SECOND)
    }

    fn open(path: &Path) -> File {
        OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path).unwrap()
    }

    fn folder() -> (TempDir, PathBuf) {
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("recorded-1.audio");
        (folder, path)
    }

    // A file of that many seconds, one track.
    fn written(seconds: u8) -> (TempDir, PathBuf) {
        let (folder, path) = folder();
        let mut writer = Writer::open(open(&path), &key(1), 1).unwrap();
        (0..seconds).for_each(|number| writer.append(&second(number)).unwrap());
        (folder, path)
    }

    fn read(path: &Path) -> Vec<Vec<Packet>> {
        let mut reader = Reader::open(open(path), &key(1)).unwrap();
        (0..reader.seconds).map(|second| reader.second(second).unwrap()).collect()
    }

    fn size(path: &Path) -> u64 {
        fs::metadata(path).unwrap().len()
    }

    fn change(path: &Path, at: u64) {
        let mut bytes = fs::read(path).unwrap();
        bytes[at as usize] ^= 1;
        fs::write(path, bytes).unwrap();
    }

    #[test]
    fn seconds_come_back_as_they_were_written_and_all_have_one_size() {
        let (_folder, path) = written(3);
        assert_eq!(read(&path), [second(0), second(1), second(2)]);
        assert_eq!(size(&path), HEADER + 3 * ONE_TRACK);

        let (_folder, path) = folder();
        let mut writer = Writer::open(open(&path), &key(1), 2).unwrap();
        let both = packets(7, 2 * FRAMES_PER_SECOND);
        writer.append(&both).unwrap();
        writer.append(&both).unwrap();
        assert_eq!(size(&path), HEADER + 2 * TWO_TRACKS);
        let mut reader = Reader::open(open(&path), &key(1)).unwrap();
        assert_eq!((reader.tracks(), reader.frames()), (2, 100));
        assert_eq!(reader.second(1).unwrap(), both);
    }

    #[test]
    fn nothing_is_kept_in_clear() {
        let (_folder, path) = written(2);
        let bytes = fs::read(&path).unwrap();
        let packet = second(1)[3];
        assert!(!bytes.windows(PACKET_BYTES).any(|window| window == packet));
        assert!(bytes.starts_with(MARK));
    }

    #[test]
    fn a_wrong_key_reads_nothing_and_cuts_nothing() {
        let (_folder, path) = written(3);
        assert_eq!(Reader::open(open(&path), &key(2)).err(), Some(AudioError::Locked));
        assert_eq!(Writer::open(open(&path), &key(2), 1).err(), Some(AudioError::Locked));
        assert_eq!(read(&path).len(), 3);
    }

    #[test]
    fn a_changed_second_is_refused_and_the_others_are_read() {
        let (_folder, path) = written(4);
        change(&path, HEADER + ONE_TRACK + 100);
        let mut reader = Reader::open(open(&path), &key(1)).unwrap();
        assert_eq!(reader.second(0).unwrap(), second(0));
        assert_eq!(reader.second(1), Err(AudioError::Locked));
        assert_eq!(reader.second(2).unwrap(), second(2));
    }

    #[test]
    fn a_changed_header_locks_every_second() {
        // The bytes kept for later and the salt: a header is sealed whole with each second.
        for at in [13, HEADER - 1] {
            let (_folder, path) = written(2);
            change(&path, at);
            assert_eq!(Reader::open(open(&path), &key(1)).err(), Some(AudioError::Locked));
        }
    }

    #[test]
    fn a_second_moved_or_taken_from_another_file_is_refused() {
        let (_folder, path) = written(3);
        let (_other_folder, other) = written(3);
        let record = |file: &Path, second: u64| {
            let from = (HEADER + second * ONE_TRACK) as usize;
            fs::read(file).unwrap()[from..from + ONE_TRACK as usize].to_vec()
        };
        let mut bytes = fs::read(&path).unwrap();
        let place = |second: u64| (HEADER + second * ONE_TRACK) as usize;
        bytes.splice(place(0)..place(1), record(&path, 1));
        bytes.splice(place(1)..place(2), record(&other, 1));
        fs::write(&path, bytes).unwrap();
        let mut reader = Reader::open(open(&path), &key(1)).unwrap();
        assert_eq!(reader.second(0), Err(AudioError::Locked));
        assert_eq!(reader.second(1), Err(AudioError::Locked));
        assert_eq!(reader.second(2).unwrap(), second(2));
    }

    #[test]
    fn a_file_cut_in_the_middle_of_a_second_reopens_and_goes_on() {
        let (_folder, path) = written(3);
        open(&path).set_len(HEADER + 2 * ONE_TRACK + 1_000).unwrap();
        assert_eq!(read(&path), [second(0), second(1)]);
        let mut writer = Writer::open(open(&path), &key(1), 1).unwrap();
        assert_eq!((writer.seconds(), writer.frames(), size(&path)), (2, 100, HEADER + 2 * ONE_TRACK));
        writer.append(&second(9)).unwrap();
        assert_eq!(writer.frames(), 150);
        assert_eq!(read(&path), [second(0), second(1), second(9)]);
    }

    #[test]
    fn seconds_the_disk_never_received_are_dropped() {
        // As a power cut leaves a file: its size written, its last bytes never, and one second half there.
        let (_folder, path) = written(3);
        let whole = fs::read(&path).unwrap();
        open(&path).set_len(HEADER + 2 * ONE_TRACK + 1_000).unwrap();
        open(&path).set_len(HEADER + (2 + UNFLUSHED) * ONE_TRACK).unwrap();
        assert_eq!(read(&path), [second(0), second(1)]);
        assert_eq!(Writer::open(open(&path), &key(1), 1).unwrap().seconds(), 2);
        assert_eq!(fs::read(&path).unwrap(), whole[..(HEADER + 2 * ONE_TRACK) as usize]);
    }

    #[test]
    fn more_unreadable_seconds_than_a_power_cut_leaves_are_not_cut() {
        let (_folder, path) = written(3);
        let unread = HEADER + (3 + UNFLUSHED + 1) * ONE_TRACK;
        open(&path).set_len(unread).unwrap();
        assert_eq!(Reader::open(open(&path), &key(1)).err(), Some(AudioError::Locked));
        assert_eq!(Writer::open(open(&path), &key(1), 1).err(), Some(AudioError::Locked));
        assert_eq!(size(&path), unread);
    }

    #[test]
    fn only_the_last_second_is_short() {
        let (_folder, path) = written(1);
        let mut writer = Writer::open(open(&path), &key(1), 1).unwrap();
        writer.append(&packets(1, 20)).unwrap();
        assert_eq!(writer.append(&second(2)), Err(AudioError::Ended));
        let mut ended = Writer::open(open(&path), &key(1), 1).unwrap();
        assert_eq!((ended.frames(), ended.append(&second(2))), (70, Err(AudioError::Ended)));
        assert_eq!(size(&path), HEADER + 2 * ONE_TRACK);
        let mut reader = Reader::open(open(&path), &key(1)).unwrap();
        assert_eq!(reader.frames(), 70);
        assert_eq!(reader.second(1).unwrap(), packets(1, 20));
        assert!(reader.second(2).unwrap().is_empty());
    }

    #[test]
    fn a_file_cut_while_it_was_made_starts_again() {
        let (_folder, path) = folder();
        fs::write(&path, &MARK[..5]).unwrap();
        assert_eq!(Reader::open(open(&path), &key(1)).err(), Some(AudioError::Absent));
        Writer::open(open(&path), &key(1), 1).unwrap().append(&second(0)).unwrap();
        assert_eq!(read(&path), [second(0)]);
        let (_folder, path) = written(0);
        assert_eq!((size(&path), read(&path).len()), (HEADER, 0));
    }

    #[test]
    fn a_file_of_a_newer_app_or_of_another_kind_is_refused_and_left_as_it_is() {
        // The mark, the version, no track, another size of packet.
        for (at, byte) in [(0, b'X'), (8, 2), (9, 0), (10, 40)] {
            let (_folder, path) = written(2);
            let mut bytes = fs::read(&path).unwrap();
            bytes[at] = byte;
            fs::write(&path, &bytes).unwrap();
            assert_eq!(Reader::open(open(&path), &key(1)).err(), Some(AudioError::Unsupported));
            assert_eq!(Writer::open(open(&path), &key(1), 1).err(), Some(AudioError::Unsupported));
            assert_eq!(fs::read(&path).unwrap(), bytes);
        }
    }

    #[test]
    fn the_tracks_of_a_file_do_not_change() {
        let (_folder, path) = written(1);
        assert_eq!(Writer::open(open(&path), &key(1), 2).err(), Some(AudioError::Tracks));
        let (_folder, path) = folder();
        assert_eq!(Writer::open(open(&path), &key(1), 0).err(), Some(AudioError::Tracks));
        let mut writer = Writer::open(open(&path), &key(1), 2).unwrap();
        for refused in [0, 3, 2 * FRAMES_PER_SECOND + 2] {
            assert_eq!(writer.append(&packets(0, refused)), Err(AudioError::Tracks));
        }
        assert_eq!(size(&path), HEADER);
    }
}
