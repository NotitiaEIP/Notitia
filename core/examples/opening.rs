// How long the meetings of a folder take to open, without and with their index (T-75). Through the same calls as an
// app, and optimised as an app is: cargo run --release --example opening
// A folder given after `--` keeps the meetings, to push them to a phone and time its launch there.
#![expect(clippy::print_stdout, clippy::disallowed_methods, reason = "a measure reads the clock and prints")]

use std::error::Error;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};
use std::{fs, thread};

use notitia::library::{Event, Library, Listener};
use notitia::meeting::{
    ActionDraft, ActionSource, Change, Edit, Extra, InvalidId, MeetingId, MinutesDraft, TranscriptLine,
};
use time::macros::utc_datetime;

const MEETINGS: u32 = 300;
// An hour of speech: a sentence every six seconds, some 9,000 words.
const LINES: usize = 600;
const ACTIONS: usize = 8;
const CHANGED: u32 = 3;

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

fn id(meeting: u32) -> Result<MeetingId, InvalidId> {
    MeetingId::new(format!("recorded-{meeting}"))
}

// As a recording leaves it: begun by the recorder, an hour long, then transcribed and summed up by the app.
fn hour(meeting: u32) -> Result<[Change; 3], InvalidId> {
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
        Change::Transcribed {
            meeting: id(meeting)?,
            transcript: said().take(LINES).map(line).collect(),
            minutes: MinutesDraft::Written {
                summary: SENTENCES.concat(),
                decisions: SENTENCES.map(String::from).into(),
                actions: said().take(ACTIONS).map(action).collect(),
            },
        },
    ])
}

// The list is what the first screen waits for; the index is written after it.
fn open(what: &str, folder: &Path) -> Result<Library, Box<dyn Error>> {
    let screen = Arc::new(Screen::default());
    let asked = Instant::now();
    let library = Library::open(folder, None, screen.clone())?;
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
    let library = Library::open(folder, None, Arc::new(Screen::default()))?;
    for meeting in 0..MEETINGS {
        hour(meeting)?.into_iter().for_each(|change| library.apply(change));
    }
    drop(library);
    let mut size = 0;
    for file in fs::read_dir(folder)? {
        size += file?.metadata()?.len();
    }
    println!("{MEETINGS} meetings of one hour, {} MB, their files still in the memory of the system", size / 1_000_000);
    // A file as recent as the index is read again: the meetings are left to age past that.
    thread::sleep(Duration::from_secs(3));
    drop(open("without the index", folder)?);
    let library = open("with the index", folder)?;
    for meeting in 0..CHANGED {
        library.apply(Change::Edit { meeting: id(meeting)?, edit: Edit::Rename { title: "Point produit".into() } });
    }
    drop(library);
    drop(open(&format!("with the index, {CHANGED} meetings changed since"), folder)?);
    Ok(())
}
