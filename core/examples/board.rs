// What the board of the actions costs (T-84): the meetings lent to a session, the board asked as an app asks it, and
// the text it tells, as the bridge sends it. At the scale of a user of a year, then ten times that.
// Optimised as an app is: cargo run --release --example board
#![expect(clippy::print_stdout, clippy::disallowed_methods, reason = "a measure reads the clock and prints")]

use std::error::Error;
use std::fs;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use notitia::meeting::{Action, ActionId, Change, Extra, Meeting, MeetingId, Minutes, MinutesState};
use notitia::{Event, Listener, Request, Session};
use time::macros::{date, utc_datetime};

const NAMES: [&str; 12] = [
    "Nathan",
    "nathan",
    "Adam",
    "Clément",
    "clement",
    "Lucas",
    "Alexandre",
    "Amine",
    "Zoé",
    "Sadik",
    "Clément B.",
    "Inès",
];
const ASKS: usize = 30;

// The size of the last board told, and how many were.
#[derive(Default)]
struct Screen {
    bytes: AtomicUsize,
    told: AtomicUsize,
}

impl Listener for Screen {
    fn on_event(&self, event: &Event) {
        if let Event::Board(_) = event {
            self.bytes.store(event.json().map_or(0, |text| text.len()), Ordering::Relaxed);
            self.told.fetch_add(1, Ordering::Relaxed);
        }
    }
}

// Made up: no meeting was recorded for this. Most of the actions are done, as in a year of use.
fn meetings(count: usize, actions: usize) -> Result<Vec<Meeting>, Box<dyn Error>> {
    let mut drawn: u64 = 0x9E37_79B9_7F4A_7C15;
    let mut next = move |bound: usize| {
        drawn ^= drawn << 13;
        drawn ^= drawn >> 7;
        drawn ^= drawn << 17;
        (drawn % bound as u64) as usize
    };
    let today = date!(2026 - 11 - 27);
    (0..count)
        .map(|index| {
            let started_at = utc_datetime!(2026-11-27 9:00) - Duration::from_secs(index as u64 * 3 * 3_600);
            let actions = (0..actions)
                .map(|rank| {
                    let owned = next(10) != 0;
                    let dated = next(10) != 0;
                    Ok(Action {
                        id: ActionId::new(rank.to_string())?,
                        text: "Préparer le document pour le client avant jeudi".into(),
                        owner: owned.then(|| NAMES[next(NAMES.len())].to_owned()),
                        due: dated.then(|| today + time::Duration::days(next(90) as i64 - 30)),
                        done: next(10) < 7,
                        reminder: false,
                        source: None,
                        extra: Extra::new(),
                    })
                })
                .collect::<Result<_, Box<dyn Error>>>()?;
            Ok(Meeting {
                id: MeetingId::new(format!("recorded-{index}"))?,
                started_at,
                offset_minutes: None,
                duration_ms: 1_800_000,
                transcribed_ms: None,
                audio_erased_at: None,
                modified_at: started_at,
                device: "phone-1".into(),
                title: None,
                speakers: Vec::new(),
                minutes: MinutesState::Written {
                    minutes: Minutes { summary: String::new(), decisions: Vec::new(), actions, extra: Extra::new() },
                },
                transcript: Vec::new(),
                extra: Extra::new(),
            })
        })
        .collect()
}

// The most memory the process held, where the system says it (Linux, Android).
fn peak_memory() -> String {
    let status = fs::read_to_string("/proc/self/status").unwrap_or_default();
    let peak = status.lines().find_map(|line| line.strip_prefix("VmHWM:"));
    peak.map_or_else(|| "unknown".into(), |kb| kb.trim().to_owned())
}

fn measure(count: usize, per_meeting: usize) -> Result<(), Box<dyn Error>> {
    let folder = tempfile::tempdir()?;
    let screen = Arc::new(Screen::default());
    let session = Session::open(folder.path(), None, screen.clone())?;
    session.apply(Request::Meetings(Change::Lend { meetings: meetings(count, per_meeting)? }));
    let mut taken = Vec::new();
    for _ in 0..ASKS {
        let again = Request::read(r#"{"type":"board","today":"2026-11-27"}"#).ok_or("the request is refused")?;
        let started = Instant::now();
        session.apply(again);
        taken.push(started.elapsed());
    }
    taken.sort();
    let millis = |time: Duration| time.as_secs_f64() * 1_000.0;
    println!(
        "{count} meetings of {per_meeting} actions: a board told in {:.2} ms at best, {:.2} ms at the median, \
         {:.2} ms at worst; {} KB of text, {} boards told; peak memory {}",
        millis(taken[0]),
        millis(taken[taken.len() / 2]),
        millis(taken[taken.len() - 1]),
        screen.bytes.load(Ordering::Relaxed) / 1_000,
        screen.told.load(Ordering::Relaxed),
        peak_memory()
    );
    Ok(())
}

fn main() -> Result<(), Box<dyn Error>> {
    measure(300, 8)?;
    measure(3_000, 10)?;
    Ok(())
}
