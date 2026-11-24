use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use serde::Serialize;

use crate::audio::{self, AudioError};
use crate::engine::lent::{Lender, Offer};
use crate::engine::{Catalogue, Engines};
use crate::library::{self, Library, LibraryError, lock};
use crate::meeting::{Change, Meeting, MeetingId};
use crate::pipeline::Pipeline;
use crate::recorder::{Command, Recorder, State};
use crate::secret::Key;

// The core as a whole: what the bridge of the phones and the computer's app call, the same functions for both.

// A player asks for passages: a minute at most per call, so no call holds an hour of sound in memory.
pub const SOUND_MS: u64 = 60_000;

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
    // Each kind, the chosen engine first: once the meetings are listed, and again at each offer.
    Engines(&'a Catalogue),
    // Already tagged with its own kind.
    #[serde(untagged)]
    Library(&'a library::Event),
}

impl Event<'_> {
    // The text an app receives, written here so that no app writes it on its own. None as for Meeting::json: an
    // event may carry a meeting.
    pub fn json(&self) -> Option<String> {
        serde_json::to_string(self).ok()
    }
}

// Called on the thread that asked for a change, on the recorder's or on the pipeline's. It may read a meeting back,
// it must not ask for anything, which would wait for itself.
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
    // First, so dropped first: its thread ends, its engine call stopped, before anything it uses goes.
    pipeline: Pipeline,
    recorder: Recorder,
    library: Arc<Library>,
    // Replaced whole, never changed: a call already under way keeps the engines it started with. Never held while the
    // app is told, so anyone may read the engines from any thread.
    engines: Engines,
    // Held while an offer takes effect and is told: two offers from two threads are told in the order they hold.
    offering: Mutex<()>,
    listener: Arc<dyn Listener>,
}

impl Session {
    // Without a key no audio is written: a recording then fails at once.
    pub fn open(
        directory: impl Into<PathBuf>,
        key: Option<Key>,
        listener: Arc<dyn Listener>,
    ) -> Result<Self, LibraryError> {
        let library = Arc::new(Library::open(directory, key, Arc::new(Relay(listener.clone())))?);
        let engines = Engines::default();
        // A system that cannot give a thread cannot keep a meeting either.
        let pipeline = Pipeline::spawn(library.clone(), engines.clone()).map_err(|_| LibraryError::Unreadable)?;
        let (waker, told) = (pipeline.waker(), listener.clone());
        let tell = move |state: &State| {
            waker.recorded(state);
            told.on_event(&Event::Recorder(state));
        };
        let recorder = Recorder::spawn(library.clone(), tell).map_err(|_| LibraryError::Unreadable)?;
        let catalogue = lock(&engines).clone();
        listener.on_event(&Event::Engines(&catalogue));
        Ok(Self { pipeline, recorder, library, engines, offering: Mutex::default(), listener })
    }

    // The engines of the app, in place of those it offered before.
    pub fn offer(&self, offer: Offer, lender: Arc<dyn Lender>) {
        let catalogue = Arc::new(offer.lend(&lender));
        let _told = lock(&self.offering);
        *lock(&self.engines) = catalogue.clone();
        // Before the app is told: a listener that panics does not keep the pipeline from the engines.
        self.pipeline.offered();
        self.listener.on_event(&Event::Engines(&catalogue));
    }

    // The engines as they are now: they stay as they are for whoever holds them.
    pub fn engines(&self) -> Arc<Catalogue> {
        lock(&self.engines).clone()
    }

    // Says nothing back: what changed comes as events.
    pub fn apply(&self, request: Request) {
        match request {
            Request::Recorder(command) => self.recorder.apply(command),
            Request::Meetings(Change::Delete { meetings }) => {
                // The recorder and the pipeline let go of the audio first: Windows refuses to delete an open file.
                // The engine call under way on one of them is stopped, and what it heard is not kept.
                self.recorder.forget(&meetings);
                self.pipeline.forget(&meetings);
                self.library.apply(Change::Delete { meetings });
            }
            Request::Meetings(change) => self.library.apply(change),
        }
    }

    // The one function called from any thread, the microphone's: it returns at once.
    pub fn hear(&self, track: u8, pcm: Vec<u8>) {
        self.recorder.hear(track, pcm);
    }

    // The whole meeting, lines included.
    pub fn meeting(&self, id: &MeetingId) -> Result<Meeting, LibraryError> {
        self.library.meeting(id)
    }

    // Ready to play: one channel, the tracks mixed. No app needs a track alone. Shorter than asked past SOUND_MS, as
    // where the audio ends.
    pub fn sound(&self, id: &MeetingId, from_ms: u64, length_ms: u64) -> Result<Vec<i16>, AudioError> {
        Ok(audio::mix(self.library.audio(id)?.read(from_ms, length_ms.min(SOUND_MS))?))
    }

    // Once the recorder has done all it was asked, and the pipeline all it can: their events are told by then.
    #[cfg(any(test, feature = "testing"))]
    pub fn settled(&self) {
        self.recorder.forget(&[]);
        self.pipeline.settled();
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::{Mutex, mpsc};

    use serde_json::Value;
    use tempfile::TempDir;
    use time::macros::utc_datetime;

    use super::*;
    use crate::audio::{FRAME_SAMPLES, FRAMES_PER_SECOND};
    use crate::engine::lent::testing::App as Lending;
    use crate::engine::{About, Engine, EngineError, Hint, Segment, SpeechEngine, Stop};
    use crate::meeting::MinutesState;
    use crate::meeting::testing::id;
    use crate::recorder::testing::sound;
    use crate::recorder::{Problem, Progress};
    use crate::secret::testing::key;
    use crate::speech::testing::{quiet, talk};
    use crate::testing::each_damaged;

    // What the apps write and read of the recorder.
    const RECORDER: &str = include_str!("../tests/fixtures/bridge/recorder.json");
    const CHANGES: &str = include_str!("../tests/fixtures/bridge/changes.json");
    const ENGINES: &str = include_str!("../tests/fixtures/bridge/engines.json");

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

    // As every app receives it: a level is written as the float it is, not widened.
    fn as_text(event: &Event) -> Value {
        serde_json::from_str(&event.json().unwrap()).unwrap()
    }

    fn opened(folder: &TempDir) -> (Arc<App>, Session) {
        let app = Arc::new(App::default());
        let session = Session::open(folder.path(), Some(key(1)), app.clone()).unwrap();
        (app, session)
    }

    fn ask(session: &Session, text: &str) {
        session.apply(Request::read(text).unwrap());
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

    fn offered(names: &[&str]) -> Offer {
        let engines: Vec<Value> = names
            .iter()
            .map(|name| serde_json::json!({"kind": "speech", "name": name, "remote": false, "longestMs": 30_000}))
            .collect();
        Offer::read(&Value::Array(engines).to_string()).unwrap()
    }

    fn chosen(session: &Session) -> Option<String> {
        session.engines().speech().map(|engine| engine.about().name.clone())
    }

    #[test]
    fn the_engines_are_told_once_the_meetings_are_listed_then_at_each_offer() {
        let fixture: Value = serde_json::from_str(ENGINES).unwrap();
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        let kinds: Vec<Value> = app.told().iter().map(|event| event["type"].clone()).collect();
        assert_eq!(kinds, ["listed", "engines"]);
        assert_eq!(app.told()[1], fixture["events"][0]);
        assert!(session.engines().speech().is_none());

        let offer = Offer::read(&fixture["offer"].to_string()).unwrap();
        session.offer(offer, Lending::answering(&[]));
        assert_eq!(app.told().last(), Some(&fixture["events"][1]));
        assert_eq!(chosen(&session).as_deref(), Some("speech-analyzer"));
        session.offer(Offer::read("[]").unwrap(), Lending::answering(&[]));
        assert_eq!(app.told().last(), Some(&fixture["events"][0]));
        assert!(session.engines().speech().is_none());
    }

    #[test]
    fn an_offer_replaces_the_engines_whole_and_a_call_keeps_those_it_started_with() {
        let folder = tempfile::tempdir().unwrap();
        let (_, session) = opened(&folder);
        session.offer(offered(&["a", "b"]), Lending::answering(&[]));
        let held = session.engines();
        session.offer(offered(&["c"]), Lending::answering(&[]));
        assert_eq!(chosen(&session).as_deref(), Some("c"));
        let told = serde_json::to_value(&*session.engines()).unwrap();
        assert_eq!(told["speech"], serde_json::json!([{"name": "c", "remote": false}]));
        assert_eq!(held.speech().map(|engine| engine.about().name.as_str()), Some("a"));
    }

    #[test]
    fn the_last_engines_told_are_those_that_hold_whatever_the_threads() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        let session = Arc::new(session);
        let threads: Vec<_> = ["a", "b", "c", "d"]
            .into_iter()
            .map(|name| {
                let session = session.clone();
                std::thread::spawn(move || {
                    for _ in 0..200 {
                        session.offer(offered(&[name]), Lending::answering(&[]));
                    }
                })
            })
            .collect();
        threads.into_iter().for_each(|thread| thread.join().unwrap());
        let last = app.told().into_iter().rev().find(|event| event["type"] == "engines").unwrap();
        assert_eq!(last["speech"][0]["name"].as_str().map(str::to_owned), chosen(&session));
    }

    // Hands each engines event to another thread, which reads the engines back, and waits for it: as an app that
    // posts to its main thread and waits would.
    #[derive(Default)]
    struct Handing {
        session: std::sync::OnceLock<Arc<Session>>,
        read: Mutex<Vec<Option<String>>>,
    }

    impl Listener for Handing {
        fn on_event(&self, event: &Event) {
            let (Event::Engines(_), Some(session)) = (event, self.session.get().cloned()) else {
                return;
            };
            let (sent, read) = std::sync::mpsc::channel();
            std::thread::spawn(move || sent.send(chosen(&session)));
            let read = read.recv_timeout(std::time::Duration::from_secs(5)).expect("the engines are held while told");
            self.read.lock().unwrap().push(read);
        }
    }

    #[test]
    fn the_engines_told_can_be_read_back_from_any_thread() {
        let folder = tempfile::tempdir().unwrap();
        let app = Arc::new(Handing::default());
        let session = Arc::new(Session::open(folder.path(), Some(key(1)), app.clone()).unwrap());
        app.session.set(session.clone()).ok().unwrap();
        session.offer(offered(&["a"]), Lending::answering(&[]));
        assert_eq!(*app.read.lock().unwrap(), [Some("a".to_owned())]);
    }

    // Panics on the engines, as a faulty app would: the engines it was told stay those of the core.
    struct Faulty;

    impl Listener for Faulty {
        fn on_event(&self, event: &Event) {
            if matches!(event, Event::Engines(catalogue) if catalogue.speech().is_some()) {
                panic!("a faulty listener");
            }
        }
    }

    #[test]
    fn a_listener_that_panics_leaves_the_engines_it_was_told() {
        let folder = tempfile::tempdir().unwrap();
        let session = Session::open(folder.path(), Some(key(1)), Arc::new(Faulty)).unwrap();
        let offer = || session.offer(offered(&["a"]), Lending::answering(&[]));
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(offer)).is_err());
        assert_eq!(chosen(&session).as_deref(), Some("a"));
        session.offer(Offer::read("[]").unwrap(), Lending::answering(&[]));
        assert_eq!(chosen(&session), None);
    }

    // The microphone's bytes of a sound: sentences someone says, each followed by a pause.
    fn said(talks_ms: &[u64]) -> Vec<u8> {
        let sound = talks_ms.iter().flat_map(|ms| [talk(*ms), quiet(2_500)].concat());
        sound.flat_map(i16::to_le_bytes).collect()
    }

    #[test]
    fn a_meeting_recorded_is_heard_by_the_engine_the_app_lent_as_it_is_said() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        let heard = "{\"type\":\"heard\",\"segments\":[{\"fromMs\":0,\"toMs\":900,\"text\":\"Bonjour à tous.\"}]}";
        let lender = Lending::answering(&[heard, heard]);
        session.offer(offered(&["ear"]), lender.clone());
        ask(&session, "{\"type\":\"start\",\"tracks\":1,\"offsetMinutes\":60}");
        // A second at a time, as a microphone gives it: the first sentence is heard before the second is said.
        let first = said(&[3_000]);
        first.chunks(32_000).for_each(|second| session.hear(0, second.to_vec()));
        session.settled();
        let meeting = app.recorded();
        assert_eq!(session.meeting(&meeting).unwrap().transcript.len(), 1);
        said(&[2_000]).chunks(32_000).for_each(|second| session.hear(0, second.to_vec()));
        ask(&session, "{\"type\":\"stop\"}");
        session.settled();
        let whole = session.meeting(&meeting).unwrap();
        let lines: Vec<(bool, Option<&str>)> =
            whole.transcript.iter().map(|line| (line.at_ms > 5_000, line.text.as_deref())).collect();
        assert_eq!(lines, [(false, Some("Bonjour à tous.")), (true, Some("Bonjour à tous."))]);
        // Owed: the app lent no engine to write them.
        assert_eq!((whole.transcribed_ms, whole.minutes), (None, MinutesState::Writing));
        let asked: Vec<Value> =
            lender.asked().iter().map(|(_, request, _)| serde_json::from_str(request).unwrap()).collect();
        let previous = ["", "Bonjour à tous."];
        let expected: Vec<Value> = previous
            .iter()
            .map(|previous| serde_json::json!({"type": "transcribe", "language": "fr", "previous": previous}))
            .collect();
        assert_eq!(asked, expected);
        let told = app.told().into_iter().filter(|event| event["meeting"]["id"] == meeting.as_str());
        let edges: Vec<Value> = told.map(|event| event["meeting"]["transcribedMs"].clone()).collect();
        assert_eq!(edges.last(), Some(&Value::Null), "the app reads how far it is heard in the meeting");
    }

    // An engine of the core, which holds its call until the test says, as a model takes seconds over a window.
    struct Holding {
        about: About,
        called: mpsc::Sender<()>,
        go: Mutex<mpsc::Receiver<()>>,
        stopped: Mutex<Vec<bool>>,
    }

    impl Engine for Holding {
        fn about(&self) -> &About {
            &self.about
        }
    }

    impl SpeechEngine for Holding {
        fn longest_ms(&self) -> u32 {
            30_000
        }

        fn transcribe(&self, _: &[i16], _: &Hint, stop: &Stop) -> Result<Vec<Segment>, EngineError> {
            self.called.send(()).unwrap();
            let _ = self.go.lock().unwrap().recv();
            self.stopped.lock().unwrap().push(stop.stopped());
            Ok(vec![Segment { from_ms: 0, to_ms: 900, text: "Bonjour à tous.".into() }])
        }
    }

    #[test]
    fn a_meeting_deleted_while_it_is_heard_stops_the_engine_and_does_not_come_back() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        ask(&session, "{\"type\":\"start\",\"tracks\":1,\"offsetMinutes\":60}");
        session.hear(0, said(&[3_000]));
        ask(&session, "{\"type\":\"stop\"}");
        session.settled();
        let meeting = app.recorded();
        let ((called, calling), (go, going)) = (mpsc::channel(), mpsc::channel());
        let about = About { name: "holding".into(), remote: false };
        let engine = Arc::new(Holding { about, called, go: Mutex::new(going), stopped: Mutex::default() });
        *lock(&session.engines) = Arc::new(Catalogue::new(vec![engine.clone()], vec![], vec![]));
        session.pipeline.offered();
        calling.recv().unwrap();
        ask(&session, &format!("{{\"type\":\"delete\",\"meetings\":[\"{}\"]}}", meeting.as_str()));
        assert!(files(&folder).is_empty());
        go.send(()).unwrap();
        session.settled();
        assert_eq!(*engine.stopped.lock().unwrap(), [true]);
        assert_eq!(session.meeting(&meeting).err(), Some(LibraryError::NotFound));
        assert!(files(&folder).is_empty());
    }

    #[test]
    fn a_meeting_deleted_while_it_is_recorded_stops_and_does_not_come_back() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        ask(&session, "{\"type\":\"start\",\"tracks\":1,\"offsetMinutes\":60}");
        session.hear(0, sound(1_200, 5_000.0, 440.0));
        session.settled();
        let meeting = app.recorded();
        assert_eq!(files(&folder).len(), 2);
        ask(&session, &format!("{{\"type\":\"delete\",\"meetings\":[\"{}\"]}}", meeting.as_str()));
        assert_eq!(app.last_state()["state"], "idle");
        assert!(files(&folder).is_empty());
        session.hear(0, sound(1_200, 5_000.0, 440.0));
        session.settled();
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
        session.hear(0, sound(1_000, 30_000.0, 440.0));
        session.hear(1, sound(1_000, 30_000.0, 440.0));
        ask(&session, "{\"type\":\"stop\"}");
        session.settled();
        let meeting = app.recorded();
        let played = session.sound(&meeting, 0, 1_000).unwrap();
        assert_eq!(played.len(), 16_000);
        assert_eq!(played.iter().max(), Some(&i16::MAX));
        assert_eq!(session.sound(&id("recorded-1"), 0, 1_000), Err(AudioError::Absent));
    }

    #[test]
    fn the_meeting_being_recorded_is_told_before_the_meetings_list_it() {
        let folder = tempfile::tempdir().unwrap();
        let (app, session) = opened(&folder);
        ask(&session, "{\"type\":\"start\",\"tracks\":1,\"offsetMinutes\":60}");
        session.hear(0, sound(100, 5_000.0, 440.0));
        session.settled();
        let meeting = app.recorded();
        let told = app.told();
        let at = |found: &dyn Fn(&Value) -> bool| told.iter().position(found).unwrap();
        let named = at(&|event| event["type"] == "recorder" && event["meeting"] == meeting.as_str());
        let listed = at(&|event| event["type"] == "changed" && event["meeting"]["id"] == meeting.as_str());
        assert!(named < listed, "{told:?}");
    }

    #[test]
    fn the_sound_of_a_meeting_comes_back_a_minute_at_most() {
        let folder = tempfile::tempdir().unwrap();
        let (_, session) = opened(&folder);
        let meeting = id("recorded-9");
        session.library.apply(Change::Begin {
            id: meeting.clone(),
            started_at: utc_datetime!(2026-11-18 9:00),
            offset_minutes: 60,
        });
        let mut writer = session.library.record(&meeting, 1).unwrap();
        // Silence encodes fast: a minute and a second of it, without the recorder.
        for _ in 0..61 * FRAMES_PER_SECOND {
            writer.write(&[[0; FRAME_SAMPLES]]).unwrap();
        }
        writer.finish().unwrap();
        let played = session.sound(&meeting, 0, u64::MAX).unwrap();
        assert_eq!(played.len() as u64, SOUND_MS * 16);
        assert_eq!(session.sound(&meeting, 30_000, 2 * SOUND_MS).unwrap().len() as u64, 31_000 * 16);
    }

    #[test]
    fn the_requests_and_the_events_of_the_recorder_cross_as_the_apps_write_and_read_them() {
        let fixture: Value = serde_json::from_str(RECORDER).unwrap();
        let requests: Vec<Request> = fixture["requests"]
            .as_array()
            .unwrap()
            .iter()
            .map(|text| Request::read(&text.to_string()).unwrap())
            .collect();
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
    fn a_damaged_request_is_refused_or_read() {
        let recorder: Value = serde_json::from_str(RECORDER).unwrap();
        let changes: Vec<Value> = serde_json::from_str(CHANGES).unwrap();
        let requests = recorder["requests"].as_array().unwrap().iter().chain(&changes);
        let texts: Vec<String> = requests.map(Value::to_string).collect();
        let good: Vec<&[u8]> = texts.iter().map(String::as_bytes).collect();
        each_damaged(&good, 2_000, |text| {
            let _ = Request::read(&String::from_utf8_lossy(text));
        });
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
            "{\"type\":\"heard\",\"meeting\":\"recorded-1\",\"lines\":[],\"fromMs\":0,\"untilMs\":null}",
        ] {
            assert_eq!(Request::read(text), None, "{text}");
        }
    }
}
