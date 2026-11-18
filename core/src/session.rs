use std::path::PathBuf;
use std::sync::Arc;

use serde::Serialize;

use crate::audio::AudioError;
use crate::library::{self, Library, LibraryError};
use crate::meeting::{Change, Meeting, MeetingId};
use crate::recorder::{Command, Recorder, State};
use crate::secret::Key;

// The core as a whole: what the bridge of the phones and the computer's app call, the same functions for both.

// What an app asks, of the recorder or of the meetings.
#[cfg_attr(test, derive(Debug, PartialEq))]
pub enum Request {
    Recorder(Command),
    Meetings(Change),
}

impl Request {
    // A command, then a change: no kind of one has the name of a kind of the other. None for a text that is
    // neither, a fault of the app and never of its user.
    pub fn read(text: &str) -> Option<Self> {
        let command = serde_json::from_str(text).map(Request::Recorder);
        command.or_else(|_| serde_json::from_str(text).map(Request::Meetings)).ok()
    }
}

// Crosses the bridge as text, written here as it stands: tests/fixtures/bridge/events.json and recorder.json hold
// one of each kind, which every app reads in its own tests.
#[derive(Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Event<'a> {
    Recorder(&'a State),
    // Already tagged with its own kind.
    #[serde(untagged)]
    Library(&'a library::Event),
}

// Called on the thread that asked for a change, or on the recorder's. It may read a meeting back, it must not ask
// for anything, which would wait for itself.
pub trait Listener: Send + Sync {
    fn on_event(&self, event: &Event);
}

struct Relay(Arc<dyn Listener>);

impl library::Listener for Relay {
    fn on_event(&self, event: &library::Event) {
        self.0.on_event(&Event::Library(event));
    }
}

pub struct Session {
    recorder: Recorder,
    library: Arc<Library>,
}

impl Session {
    // Without a key no audio is written: a recording then fails at once.
    pub fn open(
        directory: impl Into<PathBuf>,
        key: Option<Key>,
        listener: Arc<dyn Listener>,
    ) -> Result<Self, LibraryError> {
        let library = Arc::new(Library::open(directory, key, Arc::new(Relay(listener.clone())))?);
        let tell = move |state: &State| listener.on_event(&Event::Recorder(state));
        // A system that cannot give a thread cannot keep a meeting either.
        let recorder = Recorder::spawn(library.clone(), tell).map_err(|_| LibraryError::Unreadable)?;
        Ok(Self { recorder, library })
    }

    // Says nothing back: what changed comes as events.
    pub fn apply(&self, request: Request) {
        match request {
            Request::Recorder(command) => self.recorder.apply(command),
            Request::Meetings(Change::Delete { meetings }) => {
                // The recorder lets go of the audio first: Windows refuses to delete an open file.
                self.recorder.forget(&meetings);
                self.library.apply(Change::Delete { meetings });
            }
            Request::Meetings(change) => self.library.apply(change),
        }
    }

    // The one function called from any thread, the microphone's: it returns at once.
    pub fn hear(&self, track: u8, pcm: &[u8]) {
        self.recorder.hear(track, pcm);
    }

    // The whole meeting, lines included.
    pub fn meeting(&self, id: &MeetingId) -> Result<Meeting, LibraryError> {
        self.library.meeting(id)
    }

    // Ready to play: one channel, the tracks added together. No app needs a track alone.
    pub fn sound(&self, id: &MeetingId, from_ms: u64, length_ms: u64) -> Result<Vec<i16>, AudioError> {
        let mut tracks = self.library.audio(id)?.read(from_ms, length_ms)?.into_iter();
        let first = tracks.next().unwrap_or_default();
        Ok(tracks.fold(first, |mut mixed, track| {
            mixed.iter_mut().zip(track).for_each(|(sample, added)| *sample = sample.saturating_add(added));
            mixed
        }))
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Mutex;

    use serde_json::Value;
    use tempfile::TempDir;
    use time::macros::{date, utc_datetime};

    use super::*;
    use crate::meeting::testing::{id, line};
    use crate::meeting::{ActionDraft, ActionSource, Extra, MinutesDraft, TranscriptLine};
    use crate::recorder::testing::sound;
    use crate::recorder::{Problem, Progress};
    use crate::secret::testing::key;

    // What the apps will write and read once they go through the recorder (T-58).
    const RECORDER: &str = include_str!("../tests/fixtures/bridge/recorder.json");
    const CHANGES: &str = include_str!("../tests/fixtures/bridge/changes.json");

    // The side of an app: each event as it crossed.
    #[derive(Default)]
    struct App(Mutex<Vec<Value>>);

    impl Listener for App {
        fn on_event(&self, event: &Event) {
            self.0.lock().unwrap().push(as_text(event));
        }
    }

    impl App {
        fn told(&self) -> Vec<Value> {
            self.0.lock().unwrap().clone()
        }

        fn last_state(&self) -> Value {
            self.told().into_iter().rev().find(|event| event["type"] == "recorder").unwrap()
        }

        // The meeting the recorder made last.
        fn recorded(&self) -> MeetingId {
            let told = self.told().into_iter().rev().find_map(|event| event["meeting"].as_str().map(str::to_owned));
            MeetingId::new(told.unwrap()).unwrap()
        }
    }

    // As the bridge sends it: a level is written as the float it is, not widened.
    fn as_text(event: &Event) -> Value {
        serde_json::from_str(&serde_json::to_string(event).unwrap()).unwrap()
    }

    fn opened(folder: &TempDir) -> (Arc<App>, Session) {
        let app = Arc::new(App::default());
        let session = Session::open(folder.path(), Some(key(1)), app.clone()).unwrap();
        (app, session)
    }

    fn ask(session: &Session, text: &str) {
        session.apply(Request::read(text).unwrap());
    }

    fn settled(session: &Session) {
        session.recorder.forget(&[]);
    }

    fn files(folder: &TempDir) -> Vec<String> {
        let names = fs::read_dir(folder.path()).unwrap().map(|entry| entry.unwrap().file_name());
        names
            .filter_map(|name| name.into_string().ok())
            .filter(|name| name.contains(".notitia") || name.contains(".audio"))
            .collect()
    }

    #[test]
    fn a_session_is_shared_by_the_threads_of_an_app() {
        fn shared<T: Send + Sync>() {}
        shared::<Session>();
    }

    #[test]
    fn a_meeting_deleted_while_it_is_recorded_stops_and_does_not_come_back() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        ask(&session, "{\"type\":\"start\",\"tracks\":1,\"offsetMinutes\":60}");
        session.hear(0, &sound(1_200, 5_000.0, 440.0));
        settled(&session);
        let meeting = app.recorded();
        assert_eq!(files(&folder).len(), 2);
        ask(&session, &format!("{{\"type\":\"delete\",\"meetings\":[\"{}\"]}}", meeting.as_str()));
        assert_eq!(app.last_state()["state"], "idle");
        assert!(files(&folder).is_empty());
        session.hear(0, &sound(1_200, 5_000.0, 440.0));
        settled(&session);
        assert_eq!((app.last_state()["state"].as_str(), files(&folder)), (Some("idle"), vec![]));
        assert_eq!(session.meeting(&meeting).err(), Some(LibraryError::NotFound));
        drop(session);
        let (_, session) = opened(&folder);
        assert_eq!(session.meeting(&meeting).err(), Some(LibraryError::NotFound));
    }

    #[test]
    fn the_sound_of_a_meeting_comes_back_ready_to_play() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        ask(&session, "{\"type\":\"start\",\"tracks\":2,\"offsetMinutes\":60}");
        // As loud as each other and in step: added, they go past full scale.
        session.hear(0, &sound(1_000, 30_000.0, 440.0));
        session.hear(1, &sound(1_000, 30_000.0, 440.0));
        ask(&session, "{\"type\":\"stop\"}");
        settled(&session);
        let meeting = app.recorded();
        let played = session.sound(&meeting, 0, 1_000).unwrap();
        assert_eq!(played.len(), 16_000);
        assert_eq!(played.iter().max(), Some(&i16::MAX));
        assert_eq!(session.sound(&id("recorded-1"), 0, 1_000), Err(AudioError::Absent));
    }

    #[test]
    fn the_requests_and_the_events_of_the_recorder_cross_as_the_apps_will_write_and_read_them() {
        let fixture: Value = serde_json::from_str(RECORDER).unwrap();
        let requests: Vec<Request> = fixture["requests"]
            .as_array()
            .unwrap()
            .iter()
            .map(|text| Request::read(&text.to_string()).unwrap())
            .collect();
        let source =
            ActionSource { quote: "Je prépare la démo.".into(), speaker: None, at_ms: 724_000, extra: Extra::new() };
        let action = ActionDraft {
            text: "Préparer la démo".into(),
            owner: Some("Lucas".into()),
            due: Some(date!(2026 - 11 - 14)),
            source: Some(source),
        };
        let silence = TranscriptLine { at_ms: 30_000, text: None, speaker: None, extra: Extra::new() };
        let expected = vec![
            Request::Recorder(Command::Start { tracks: 1, offset_minutes: 60 }),
            Request::Recorder(Command::Start { tracks: 2, offset_minutes: -210 }),
            Request::Recorder(Command::Pause),
            Request::Recorder(Command::Resume),
            Request::Recorder(Command::Stop),
            Request::Recorder(Command::Cut { problem: Problem::Denied }),
            Request::Recorder(Command::Cut { problem: Problem::Busy }),
            Request::Recorder(Command::Cut { problem: Problem::Unavailable }),
            Request::Recorder(Command::Dismiss),
            Request::Meetings(Change::Transcribed {
                meeting: id("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f"),
                transcript: vec![line(0, "Bonjour à tous."), silence.clone()],
                minutes: MinutesDraft::Writing,
            }),
            Request::Meetings(Change::Transcribed {
                meeting: id("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f"),
                transcript: vec![line(0, "Bonjour à tous."), silence.clone(), line(724_000, "Je prépare la démo.")],
                minutes: MinutesDraft::Written {
                    summary: "Point sur la démo.".into(),
                    decisions: vec!["La démo a lieu le 15 novembre.".into()],
                    actions: vec![action],
                },
            }),
        ];
        assert_eq!(requests, expected);

        let begun = Progress { meeting: None, started_at: utc_datetime!(2026-11-18 9:00), recorded_ms: 0 };
        let heard = Progress {
            meeting: Some(id("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f")),
            recorded_ms: 61_000,
            ..begun.clone()
        };
        let states = [
            State::Idle,
            State::Failed { problem: Problem::Storage },
            State::Recording { progress: begun, level: 0.0 },
            State::Recording { progress: heard.clone(), level: 0.42 },
            State::Paused(heard.clone()),
            State::Interrupted(heard),
        ];
        let removed = library::Event::Removed { meetings: vec![id("recorded-1")] };
        let mut events: Vec<Value> = states.iter().map(|state| as_text(&Event::Recorder(state))).collect();
        events.push(as_text(&Event::Library(&removed)));
        assert_eq!(Value::Array(events), fixture["events"]);
    }

    #[test]
    fn every_change_the_apps_write_is_still_a_request() {
        let changes: Vec<Value> = serde_json::from_str(CHANGES).unwrap();
        for change in changes {
            let read = serde_json::from_value(change.clone()).unwrap();
            assert_eq!(Request::read(&change.to_string()), Some(Request::Meetings(read)));
        }
    }

    #[test]
    fn what_is_neither_a_command_nor_a_change_is_refused() {
        for text in [
            "",
            "{}",
            "{\"type\":\"start\"}",
            "{\"type\":\"start\",\"tracks\":256,\"offsetMinutes\":0}",
            "{\"type\":\"cut\",\"problem\":\"storage2\"}",
            "{\"type\":\"begin\",\"id\":\"recorded-1\",\"startedAt\":\"2026-11-11T07:00:00Z\",\"offsetMinutes\":60}",
            "{\"type\":\"forget\",\"meetings\":[]}",
        ] {
            assert_eq!(Request::read(text), None, "{text}");
        }
    }
}
