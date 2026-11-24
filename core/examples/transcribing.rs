// What the core costs around an engine for an hour of a meeting (T-94): the windows read from the sealed audio, sent
// to an engine of the app as the bridge sends them, the lines added to the meeting's file one window at a time. Then
// a launch that takes the hour up at its middle. The engine answers at once: only the core is measured.
// Optimised as an app is: cargo run --release --example transcribing
#![expect(clippy::print_stdout, clippy::disallowed_methods, reason = "a measure reads the clock and prints")]

mod common;

use std::error::Error;
use std::fs;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use notitia::library::{self, Library};
use notitia::meeting::{Change, MeetingId, TranscriptLine};
use notitia::secret::Key;
use notitia::{Event, Lender, Listener, Offer, Session};
use time::macros::utc_datetime;

const OFFER: &str = r#"[{"kind":"speech","name":"ready","remote":false,"longestMs":30000}]"#;

// What a model hears in 30 s of speech, some 75 words: the file grows as a real one does.
const SAID: &str = "On commence par le budget du trimestre, puis on passe aux recrutements prévus pour janvier. \
    Je propose de repousser la démonstration d'une semaine pour laisser le temps aux tests. Le client a demandé un \
    devis détaillé avant la fin du mois, il faut s'en occuper cette semaine. D'accord, je prépare le document et je \
    l'envoie à tout le monde avant vendredi midi, et on refait un point jeudi matin.";

// An engine of the app that hears every window at once, as many as it is allowed, then is no longer available.
struct Ready {
    left: AtomicUsize,
}

impl Lender for Ready {
    fn run(&self, _: &str, _: &str, sound: Vec<u8>) -> String {
        if self.left.try_update(Ordering::AcqRel, Ordering::Acquire, |left| left.checked_sub(1)).is_err() {
            return r#"{"type":"unavailable"}"#.into();
        }
        let to_ms = sound.len() / 32;
        format!(r#"{{"type":"heard","segments":[{{"fromMs":0,"toMs":{to_ms},"text":"{SAID}"}}]}}"#)
    }
}

// How far the meeting is heard, each time that changes: None once it is whole.
struct Heard(Mutex<Sender<Option<u64>>>);

impl Listener for Heard {
    fn on_event(&self, event: &Event) {
        if let Event::Library(library::Event::Changed { meeting, .. }) = event {
            let _ = self.0.lock().map(|sent| sent.send(meeting.transcribed_ms));
        }
    }
}

// The key of the app, never copied: each user of it is given its own.
fn key() -> Result<Key, Box<dyn Error>> {
    Ok(Key::new(&[7; 32])?)
}

// Begun by the recorder and recorded whole, as a killed app leaves a meeting it never heard.
fn recorded(folder: &Path, meeting: &MeetingId) -> Result<(), Box<dyn Error>> {
    let library = Library::open(folder, Some(key()?), Arc::new(Silent))?;
    let started_at = utc_datetime!(2026-11-24 9:00);
    library.apply(Change::Begin { id: meeting.clone(), started_at, offset_minutes: 60 });
    let path = folder.join(format!("{}.audio", meeting.as_str()));
    common::seal_hour(fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?, &key()?)?;
    Ok(())
}

struct Silent;

impl library::Listener for Silent {
    fn on_event(&self, _: &library::Event) {}
}

// The session, and how far the meeting is heard each time that changes.
type Opened = (Session, Receiver<Option<u64>>);

fn open(folder: &Path, windows: usize) -> Result<Opened, Box<dyn Error>> {
    let (sent, heard) = mpsc::channel();
    let session = Session::open(folder, Some(key()?), Arc::new(Heard(Mutex::new(sent))))?;
    let ready: Arc<dyn Lender> = Arc::new(Ready { left: AtomicUsize::new(windows) });
    session.offer(Offer::read(OFFER).ok_or("the offer is refused")?, ready);
    Ok((session, heard))
}

// The time each window took, from the one before, until the meeting is whole or `windows` were heard.
fn hear(heard: &Receiver<Option<u64>>, windows: usize, since: Instant) -> Vec<Duration> {
    let (mut taken, mut last) = (Vec::new(), since);
    while taken.len() < windows {
        // A window, or the end, which reaches no new edge.
        match heard.recv() {
            Ok(Some(_)) => {}
            Ok(None) | Err(_) => break,
        }
        taken.push(last.elapsed());
        last = Instant::now();
    }
    taken
}

fn millis(time: Duration) -> f64 {
    time.as_secs_f64() * 1_000.0
}

// The most memory the process held, where the system says it (Linux, Android).
fn peak_memory() -> String {
    let status = fs::read_to_string("/proc/self/status").unwrap_or_default();
    let peak = status.lines().find_map(|line| line.strip_prefix("VmHWM:"));
    peak.map_or_else(|| "unknown".into(), |kb| kb.trim().to_owned())
}

fn lines(session: &Session, meeting: &MeetingId) -> Result<Vec<TranscriptLine>, Box<dyn Error>> {
    Ok(session.meeting(meeting)?.transcript)
}

fn main() -> Result<(), Box<dyn Error>> {
    let meeting = MeetingId::new("recorded-1")?;

    let folder = tempfile::tempdir()?;
    recorded(folder.path(), &meeting)?;
    let started = Instant::now();
    let (session, heard) = open(folder.path(), usize::MAX)?;
    let taken = hear(&heard, usize::MAX, started);
    let whole = started.elapsed();
    let transcript = lines(&session, &meeting)?;
    drop(session);
    let windows = taken.len();
    let slowest = taken.iter().max().copied().unwrap_or_default();
    let file = fs::metadata(folder.path().join("recorded-1.notitia"))?.len();
    println!(
        "an hour heard in {:.2} s: {windows} windows, {:.1} ms each on average, the slowest {:.1} ms",
        whole.as_secs_f64(),
        millis(whole) / windows as f64,
        millis(slowest)
    );
    println!(
        "{} lines, a file of {} KB written again at each window, some {:.1} MB in all",
        transcript.len(),
        file / 1_000,
        (file * windows as u64) as f64 / 2e6
    );

    let folder = tempfile::tempdir()?;
    recorded(folder.path(), &meeting)?;
    let (session, heard) = open(folder.path(), windows / 2)?;
    hear(&heard, windows / 2, Instant::now());
    let closing = Instant::now();
    drop(session);
    let closed = closing.elapsed();
    let launched = Instant::now();
    let (session, heard) = open(folder.path(), usize::MAX)?;
    let first = hear(&heard, 1, launched);
    hear(&heard, usize::MAX, Instant::now());
    let resumed = lines(&session, &meeting)? == transcript;
    println!(
        "closed in {:.1} ms at window {}; launched again, the next window heard {:.1} ms after the launch",
        millis(closed),
        windows / 2,
        first.first().map_or(f64::NAN, |taken| millis(*taken))
    );
    println!("the text taken up is the text heard without a stop: {resumed}; peak memory {}", peak_memory());
    Ok(())
}
