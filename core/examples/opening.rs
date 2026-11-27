// How long the meetings of a folder take to open, without and with their index (T-75), sealed (T-96), then with their
// audio and their minutes owed, as on a phone with no model yet (T-95), how long the files written in clear before the
// seal take to be sealed at the first launch, and how long their audio takes to be erased once kept 30 days (T-97).
// Through the same calls as an app, and optimised as an app is:
// cargo run --release --example opening
// A folder given after `--` keeps the meetings, to push them to a phone and time its launch there.
#![expect(clippy::print_stdout, clippy::disallowed_methods, reason = "a measure reads the clock and prints")]

use std::error::Error;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};
use std::{fs, thread};

use notitia::audio::{FRAME_MS, FRAME_SAMPLES};
use notitia::format;
use notitia::library::{Event, Library, Listener};
use notitia::meeting::{
    ActionDraft, ActionSource, Change, Edit, Extra, InvalidId, MeetingId, MinutesDraft, TranscriptLine,
};
use notitia::secret::Key;
use notitia::{Request, Session};
use time::macros::utc_datetime;

const MEETINGS: u32 = 300;
// An hour of speech: a sentence every six seconds, some 9,000 words.
const LINES: usize = 600;
const ACTIONS: usize = 8;
const CHANGED: u32 = 3;
// Recorded, their minutes waiting for a model: each with as much audio as it says it lasted.
const RECORDED_MS: u64 = 10_000;

// Made up: no meeting was recorded for this.
const SENTENCES: [&str; 6] = [
    "On commence par le budget du trimestre, puis on passe aux recrutements prévus pour janvier.",
    "Je propose de repousser la démonstration d'une semaine pour laisser le temps aux tests.",
    "Le client a demandé un devis détaillé avant la fin du mois, il faut s'en occuper cette semaine.",
    "D'accord, je prépare le document et je l'envoie à tout le monde avant vendredi midi.",
    "Il reste la question du prestataire : on garde le même ou on lance un nouvel appel d'offres ?",
    "On refait un point jeudi matin, chacun arrive avec ses chiffres à jour.",
];

#[derive(Default)]
struct Screen {
    listed: OnceLock<Instant>,
}

impl Listener for Screen {
    fn on_event(&self, event: &Event) {
        if let Event::Listed { .. } = event {
            let _ = self.listed.set(Instant::now());
        }
    }
}

// The app of a tidy-up, which is told nothing it waits for.
struct Away;

impl notitia::Listener for Away {
    fn on_event(&self, _: &notitia::Event) {}
}

fn id(meeting: u32) -> Result<MeetingId, InvalidId> {
    MeetingId::new(format!("recorded-{meeting}"))
}

// As a recording leaves it: begun by the recorder, an hour long, then heard and summed up by the core.
fn hour(meeting: u32) -> Result<[Change; 4], InvalidId> {
    let said = || SENTENCES.iter().cycle().zip((0..).step_by(6_000));
    let line = |(text, at_ms): (&&str, u64)| TranscriptLine {
        at_ms,
        text: Some((*text).into()),
        speaker: None,
        extra: Extra::new(),
    };
    let action = |(text, at_ms): (&&str, u64)| ActionDraft {
        text: (*text).into(),
        owner: Some("Lucas".into()),
        due: None,
        source: Some(ActionSource { quote: (*text).into(), speaker: None, at_ms, extra: Extra::new() }),
    };
    let started_at = utc_datetime!(2026-01-05 9:00) + Duration::from_secs(3_600) * meeting;
    Ok([
        Change::Begin { id: id(meeting)?, started_at, offset_minutes: 60 },
        Change::Lasted { meeting: id(meeting)?, duration_ms: 3_600_000 },
        Change::Heard {
            meeting: id(meeting)?,
            lines: said().take(LINES).map(line).collect(),
            from_ms: 0,
            until_ms: None,
        },
        Change::Drafted {
            meeting: id(meeting)?,
            minutes: MinutesDraft::Written {
                summary: SENTENCES.concat(),
                decisions: SENTENCES.map(String::from).into(),
                actions: said().take(ACTIONS).map(action).collect(),
            },
        },
    ])
}

// The key of the app, never copied: each user of it is given its own.
fn key() -> Result<Key, Box<dyn Error>> {
    Ok(Key::new(&[7; 32])?)
}

// Begun by the recorder and stopped, its audio beside it, never transcribed.
fn recorded(library: &Library, meeting: u32) -> Result<(), Box<dyn Error>> {
    let started_at = utc_datetime!(2026-01-05 9:00) + Duration::from_secs(3_600) * meeting;
    library.apply(Change::Begin { id: id(meeting)?, started_at, offset_minutes: 60 });
    library.apply(Change::Lasted { meeting: id(meeting)?, duration_ms: RECORDED_MS });
    let mut writer = library.record(&id(meeting)?, 1)?;
    for _ in 0..RECORDED_MS / FRAME_MS {
        writer.write(&[[0; FRAME_SAMPLES]])?;
    }
    Ok(writer.finish()?)
}

// The list is what the first screen waits for; the index is written after it.
fn open(what: &str, folder: &Path, key: Option<Key>) -> Result<Library, Box<dyn Error>> {
    let screen = Arc::new(Screen::default());
    let asked = Instant::now();
    let library = Library::open(folder, key, screen.clone())?;
    let opened = asked.elapsed();
    let listed = screen.listed.get().map_or(opened, |listed| listed.duration_since(asked));
    println!("{what:<42} list after {:>7.1} ms, opened after {:>7.1} ms", millis(listed), millis(opened));
    Ok(library)
}

fn millis(time: Duration) -> f64 {
    time.as_secs_f64() * 1_000.0
}

fn main() -> Result<(), Box<dyn Error>> {
    let temporary = tempfile::tempdir()?;
    let kept = std::env::args_os().nth(1).map(PathBuf::from);
    let folder = kept.as_deref().unwrap_or(temporary.path());
    let library = Library::open(folder, Some(key()?), Arc::new(Screen::default()))?;
    for meeting in 0..MEETINGS {
        hour(meeting)?.into_iter().for_each(|change| library.apply(change));
    }
    // The same meetings as the app wrote them before the seal, in clear.
    let clear = tempfile::tempdir()?;
    for meeting in 0..MEETINGS {
        let whole = library.meeting(&id(meeting)?)?;
        fs::write(clear.path().join(format!("{}.notitia", whole.id.as_str())), format::encode(&whole)?)?;
    }
    drop(library);
    let mut size = 0;
    for file in fs::read_dir(folder)? {
        size += file?.metadata()?.len();
    }
    println!("{MEETINGS} meetings of one hour, {} MB, their files still in the memory of the system", size / 1_000_000);
    // A file as recent as the index is read again: the meetings are left to age past that.
    thread::sleep(Duration::from_secs(3));
    drop(open("without the index", folder, Some(key()?))?);
    let library = open("with the index", folder, Some(key()?))?;
    for meeting in 0..CHANGED {
        library.apply(Change::Edit { meeting: id(meeting)?, edit: Edit::Rename { title: "Point produit".into() } });
    }
    drop(library);
    drop(open(&format!("with the index, {CHANGED} meetings changed since"), folder, Some(key()?))?);
    drop(open("written in clear, sealed at this launch", clear.path(), Some(key()?))?);
    thread::sleep(Duration::from_secs(3));
    drop(open("sealed at the launch before, without the index", clear.path(), Some(key()?))?);

    let owed = tempfile::tempdir()?;
    let library = Library::open(owed.path(), Some(key()?), Arc::new(Screen::default()))?;
    for meeting in 0..MEETINGS {
        recorded(&library, meeting)?;
    }
    drop(library);
    println!("{MEETINGS} meetings recorded, {} s of audio each, their minutes owed", RECORDED_MS / 1_000);
    thread::sleep(Duration::from_secs(3));
    drop(open("with their audio", owed.path(), Some(key()?))?);
    // The best of five: what the core costs, not what the system was doing meanwhile.
    let mut best = Duration::MAX;
    for _ in 0..5 {
        let asked = Instant::now();
        drop(Library::open(owed.path(), Some(key()?), Arc::new(Screen::default()))?);
        best = best.min(asked.elapsed());
    }
    println!("{:<42} opened after {:>7.1} ms", "with their audio, the best of five", millis(best));
    // Begun in January: past their 30 days, as the first tidy-up of a phone of the team finds them. Each audio goes, and
    // each meeting, its transcript closed, is written again.
    let session = Session::open(owed.path(), Some(key()?), Arc::new(Away))?;
    let asked = Instant::now();
    session.apply(Request::read("{\"type\":\"tidy\"}").ok_or("not a request")?);
    let tidied = asked.elapsed();
    let mut left = 0;
    for file in fs::read_dir(owed.path())? {
        left += usize::from(file?.file_name().to_string_lossy().ends_with(".audio"));
    }
    println!("{:<42} erased after {:>7.1} ms, {left} left", format!("{MEETINGS} audios kept 30 days"), millis(tidied));
    Ok(())
}
