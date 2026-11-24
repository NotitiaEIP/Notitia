// What cutting an hour of a meeting into windows costs, from the sealed audio to the last window, and taking it up
// near its end (T-90). Optimised as an app is: cargo run --release --example windows
#![expect(clippy::print_stdout, clippy::disallowed_methods, reason = "a measure reads the clock and prints")]

mod common;

use std::error::Error;
use std::fs;
use std::time::Instant;

use notitia::audio::{Reader, SAMPLE_RATE};
use notitia::secret::Key;
use notitia::speech::{Next, Windows};

const ENGINE_MS: u32 = 30_000;

fn main() -> Result<(), Box<dyn Error>> {
    let folder = tempfile::tempdir()?;
    let path = folder.path().join("hour.audio");
    let open = || fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&path);
    let key = Key::new(&[7; 32])?;
    common::seal_hour(open()?, &key)?;

    let started = Instant::now();
    let (mut windows, mut reader) = (Windows::at(0, ENGINE_MS), Reader::open(open()?, &key)?);
    let (mut found, mut forced, mut sent_ms, mut longest_ms, mut near_the_end) = (0, 0, 0, 0, None);
    while let Next::Window(window) = windows.next(&mut reader, true)? {
        let length_ms = (window.sound.len() * 1_000 / SAMPLE_RATE) as u64;
        (found, sent_ms, longest_ms) = (found + 1, sent_ms + length_ms, longest_ms.max(length_ms));
        forced += usize::from(window.overlap_ms > 0);
        if window.until_ms >= 59 * 60_000 {
            near_the_end.get_or_insert(window.until_ms);
        }
    }
    let cut = started.elapsed().as_secs_f64();
    println!("an hour cut into {found} windows in {:.2} s, {:.0} times faster than it is heard", cut, 3_600.0 / cut);
    println!(
        "{:.1} minutes sent of the 60 heard, the silence left out; the longest window {:.1} s; {forced} cuts in the middle of speech",
        sent_ms as f64 / 60_000.0,
        longest_ms as f64 / 1_000.0
    );

    let edge = near_the_end.ok_or("no window near the end")?;
    let started = Instant::now();
    let mut windows = Windows::at(edge, ENGINE_MS);
    windows.next(&mut Reader::open(open()?, &key)?, true)?;
    println!(
        "taken up at {}:{:02}, the next window found in {:.0} ms",
        edge / 60_000,
        edge / 1_000 % 60,
        started.elapsed().as_secs_f64() * 1e3
    );
    Ok(())
}
