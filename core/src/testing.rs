// What the tests of several modules share, and the pins of the witnesses of the formats. Never built into an app.

use std::fs;
use std::panic::{self, AssertUnwindSafe};
use std::path::Path;

use crate::secret::testing::fingerprint;

// Values at the edge of what a field takes, which flipped bits would take millions of cases to reach: a number past
// u64, an instant that leaves the years of the format once in UTC, a name Windows keeps, a way out of the folder.
const WORDS: &[&[u8]] = &[
    b"null",
    b"true",
    b"0",
    b"-1",
    b"18446744073709551615",
    b"18446744073709551616",
    b"1e309",
    b"[]",
    b"{}",
    b"\"\"",
    b"\"\\u0000\"",
    b"\"\\ud800\"",
    b"\"con\"",
    b"\"../x\"",
    b"\"9999-12-31T23:59:59-01:00\"",
    b"\"0000-01-01T00:00:00+01:00\"",
    b"\"2026-12-31T23:59:60Z\"",
    b"\"2026-02-29\"",
    b"\"-0001-01-01\"",
];

// What a length or a count is likeliest to break on.
const EDGES: [u8; 6] = [0x00, 0x01, 0x02, 0x7f, 0x80, 0xff];

// A header says how to read the rest: half the damage falls in the first bytes.
const HEADER: usize = 64;

// Few enough for the CI on three systems, in debug. As many as wished, in release:
// NOTITIA_CASES=1000000 cargo test --release -p notitia damaged
pub fn cases(default: usize) -> usize {
    std::env::var("NOTITIA_CASES").ok().and_then(|cases| cases.parse().ok()).unwrap_or(default)
}

// Hands `read` good files damaged as a disk, a cut or another app would, the same ones at every run and on every
// system: a panic names its case, and the case comes back as it was.
pub fn each_damaged(good: &[&[u8]], default: usize, mut read: impl FnMut(&[u8])) {
    let mut draw = Draw(0x006e_6f74_6974_6961);
    for case in 0..cases(default) {
        let file = draw.damaged(good);
        if panic::catch_unwind(AssertUnwindSafe(|| read(&file))).is_err() {
            panic!("damaged case {case}, {} bytes: {}", file.len(), file[..file.len().min(512)].escape_ascii());
        }
    }
}

// SplitMix64: the same draws on every system, spread enough for damage, or for the noise of a room.
pub struct Draw(pub u64);

impl Draw {
    pub fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mixed = (self.0 ^ (self.0 >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        let mixed = (mixed ^ (mixed >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        mixed ^ (mixed >> 31)
    }

    // From 0 to `bound`, `bound` left out.
    pub fn below(&mut self, bound: usize) -> usize {
        (self.next() % bound.max(1) as u64) as usize
    }

    fn bytes(&mut self, count: usize) -> Vec<u8> {
        (0..count).map(|_| self.next() as u8).collect()
    }

    // A place in the file, its end included.
    fn place(&mut self, length: usize) -> usize {
        let within = if self.below(2) == 0 { length.min(HEADER) } else { length };
        self.below(within + 1)
    }

    // One in ten is noise alone, the others a good file damaged one to three times.
    fn damaged(&mut self, good: &[&[u8]]) -> Vec<u8> {
        if self.below(10) == 0 {
            let length = self.below(256);
            return self.bytes(length);
        }
        let mut file = good[self.below(good.len())].to_vec();
        for _ in 0..=self.below(3) {
            self.damage(&mut file, good);
        }
        file
    }

    // A word four times in ten: it leaves a file well formed, so the code that reads what a field means is reached.
    // The rest breaks the file itself.
    fn damage(&mut self, file: &mut Vec<u8>, good: &[&[u8]]) {
        let at = self.place(file.len());
        match self.below(10) {
            0 => {
                let bit = 1u8 << self.below(8);
                if let Some(byte) = file.get_mut(at) {
                    *byte ^= bit;
                }
            }
            1 => {
                let edge = EDGES[self.below(EDGES.len())];
                if let Some(byte) = file.get_mut(at) {
                    *byte = edge;
                }
            }
            2 => file.truncate(at),
            3 => {
                let length = 1 + self.below(16);
                let noise = self.bytes(length);
                file.splice(at..at, noise);
            }
            // Over and over: a nesting no parser may follow down to the end of its stack.
            4 => {
                let end = (at + 1 + self.below(32)).min(file.len());
                let repeated = file[at..end].repeat(1 + self.below(64));
                file.splice(at..at, repeated);
            }
            // Bytes that are right, in the wrong place.
            5 => {
                let other = good[self.below(good.len())];
                let from = self.below(other.len());
                let to = (from + 1 + self.below(64)).min(other.len());
                let end = (at + self.below(64)).min(file.len());
                file.splice(at..end, other[from..to].iter().copied());
            }
            _ => {
                let word = WORDS[self.below(WORDS.len())];
                let (start, end) = value_at(file, at).unwrap_or((at, at));
                file.splice(start..end, word.iter().copied());
            }
        }
    }
}

// The first value at or after `at`: a quoted text that names no field, or a number. None when no value follows.
fn value_at(file: &[u8], mut at: usize) -> Option<(usize, usize)> {
    let starts = |byte: &u8| *byte == b'"' || *byte == b'-' || byte.is_ascii_digit();
    let number = |byte: &u8| byte.is_ascii_digit() || b"+-.eE".contains(byte);
    loop {
        let start = at + file.get(at..)?.iter().position(starts)?;
        let end = if file[start] == b'"' {
            start + 2 + file[start + 1..].iter().position(|byte| *byte == b'"')?
        } else {
            start + file[start..].iter().position(|byte| !number(byte)).unwrap_or(file.len() - start)
        };
        if file[end..].iter().find(|byte| !byte.is_ascii_whitespace()) != Some(&b':') {
            return Some((start, end));
        }
        at = end;
    }
}

// Every witness of a format, as an app first wrote it, and its SHA-256 (docs/adr/0009-garde-fous.md). A format is for
// ever: a witness that changes is a file every device already holds, which the core no longer reads the same way. A
// new version comes with a witness of its own and a line here; no line is ever changed.
const WITNESSES: [(&str, &str); 6] = [
    ("audio-v1.audio", "99b21bbd32f4febdf4aaad03b79a8f5390a973fa1e9b145c9f18800eefef95d4"),
    ("meeting-sealed-v1.notitia", "9688ec766785cce9f1bdeb44432b0b50165340530bf69673bcfc718ad731ee6f"),
    ("meeting-v1-recording.notitia", "e55012494920ac5e094fb08f047a6d3808f631760ccc44a092cc8ced5675d3ed"),
    ("meeting-v1-reminder.notitia", "5995e3cd73081b3f0ad060cdc5ec49a822b48a6716d563b3b004c559ebea878c"),
    ("meeting-v1.notitia", "8d96f57bfa1c76dc6b04822888d5726c29810ea2515b2a65f1075fcc38fadcc3"),
    ("meeting-v2.notitia", "122f1bc7ccbfe30552c1aed01e96eb7083bb5fa625a7d3633027822a81c8f15c"),
];

#[test]
fn the_witnesses_of_the_formats_are_the_files_first_written() {
    let folder = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    // The examples of the bridge live in a folder of their own: they change with both sides of it. A name with a dot
    // first is a system's own, as macOS leaves one in every folder it shows.
    let mut found: Vec<String> = fs::read_dir(&folder)
        .unwrap()
        .map(|entry| entry.unwrap())
        .filter(|entry| entry.file_type().unwrap().is_file())
        .map(|entry| entry.file_name().into_string().unwrap())
        .filter(|name| !name.starts_with('.'))
        .collect();
    found.sort();
    assert_eq!(found, WITNESSES.map(|(name, _)| name), "a witness is pinned here as soon as it is written");
    for (name, pinned) in WITNESSES {
        assert_eq!(
            fingerprint(&fs::read(folder.join(name)).unwrap()),
            pinned,
            "{name}: add a version, never change one"
        );
    }
}
