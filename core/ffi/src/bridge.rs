use std::fmt;
use std::sync::Arc;

use notitia::{AudioError, Event, Key, LibraryError, Listener, MeetingId, Request, Session};

// The core as the phones reach it, and the only crate that speaks to UniFFI: the computer calls the core without it.
// Text goes in and text comes out, the JSON of requests, events and meetings; only the sound and the key of the app
// cross as bytes. A new request or a new event touches neither RustCore.kt nor RustCore.swift, which are written by
// hand and, for Swift, compiled by the CI alone.
#[derive(uniffi::Object)]
pub struct Bridge {
    session: Session,
}

// Held by the app. Called on the thread that asked for a change, before its file is written, or on the recorder's.
// It may read a meeting back, it must not ask for anything, which would wait for itself.
#[uniffi::export(with_foreign)]
pub trait BridgeListener: Send + Sync {
    fn on_event(&self, event: String);
}

// Carries no text of a meeting: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq, uniffi::Error)]
pub enum BridgeError {
    // No meeting has this identifier, or it has no audio.
    NotFound,
    // The folder, the file of the meeting or its audio.
    Unreadable,
    // The text is not a request, or the key not 32 bytes: a fault of the app, never of its user.
    Invalid,
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            BridgeError::NotFound => "no meeting, or no audio, has this identifier",
            BridgeError::Unreadable => "the meetings or their audio cannot be read",
            BridgeError::Invalid => "the text is not a request, or the key is not 32 bytes",
        })
    }
}

impl std::error::Error for BridgeError {}

impl From<LibraryError> for BridgeError {
    fn from(error: LibraryError) -> Self {
        match error {
            LibraryError::NotFound => BridgeError::NotFound,
            LibraryError::Unreadable => BridgeError::Unreadable,
        }
    }
}

impl From<AudioError> for BridgeError {
    fn from(error: AudioError) -> Self {
        match error {
            AudioError::Absent => BridgeError::NotFound,
            AudioError::Locked
            | AudioError::Unsupported
            | AudioError::Unreadable
            | AudioError::Codec
            | AudioError::Tracks
            | AudioError::Ended => BridgeError::Unreadable,
        }
    }
}

struct Told(Arc<dyn BridgeListener>);

impl Listener for Told {
    fn on_event(&self, event: &Event) {
        if let Some(event) = event.json() {
            self.0.on_event(event);
        }
    }
}

#[uniffi::export]
impl Bridge {
    // The key of the app is wiped once read. Without one the meetings open, but no audio is written nor read.
    #[uniffi::constructor]
    pub fn open(
        directory: String,
        key: Option<Vec<u8>>,
        listener: Arc<dyn BridgeListener>,
    ) -> Result<Self, BridgeError> {
        let key = key.map(Key::take).transpose().map_err(|_| BridgeError::Invalid)?;
        Ok(Self { session: Session::open(directory, key, Arc::new(Told(listener)))? })
    }

    // The whole meeting, lines included, as its file holds it.
    pub fn meeting(&self, id: String) -> Result<String, BridgeError> {
        self.session.meeting(&named(id)?)?.json().ok_or(BridgeError::Unreadable)
    }

    // Says nothing back but a text it cannot read: what changed, and a file that could not be written, come as
    // events.
    pub fn apply(&self, request: String) -> Result<(), BridgeError> {
        self.session.apply(Request::read(&request).ok_or(BridgeError::Invalid)?);
        Ok(())
    }

    // The one function called from any thread, the microphone's: it returns at once.
    pub fn hear(&self, track: u8, pcm: Vec<u8>) {
        self.session.hear(track, pcm);
    }

    // As the microphone gives it, 16-bit little-endian at 16 kHz, ready to play: a minute at most per call.
    pub fn sound(&self, id: String, from_ms: u64, length_ms: u64) -> Result<Vec<u8>, BridgeError> {
        let samples = self.session.sound(&named(id)?, from_ms, length_ms)?;
        let mut pcm = Vec::with_capacity(samples.len() * 2);
        samples.iter().for_each(|sample| pcm.extend_from_slice(&sample.to_le_bytes()));
        Ok(pcm)
    }
}

// An identifier that cannot name a file names no meeting.
fn named(id: String) -> Result<MeetingId, BridgeError> {
    MeetingId::new(id).map_err(|_| BridgeError::NotFound)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Mutex;

    use notitia::recorder::testing::sound;
    use serde_json::{Value, json};
    use tempfile::TempDir;

    use super::*;

    // The side of an app: each event as it crossed.
    #[derive(Default)]
    struct App(Mutex<Vec<Value>>);

    impl BridgeListener for App {
        fn on_event(&self, event: String) {
            self.0.lock().unwrap().push(serde_json::from_str(&event).unwrap());
        }
    }

    impl App {
        fn told(&self) -> Vec<String> {
            self.0.lock().unwrap().iter().map(|event| event["type"].as_str().unwrap().to_owned()).collect()
        }

        fn last(&self, kind: &str) -> Value {
            self.0.lock().unwrap().iter().rev().find(|event| event["type"] == kind).cloned().unwrap()
        }
    }

    // Made up, like every key of the tests.
    fn key() -> Option<Vec<u8>> {
        Some(vec![7; 32])
    }

    fn opened() -> (TempDir, Arc<App>, Bridge) {
        let folder = tempfile::tempdir().unwrap();
        let app = Arc::new(App::default());
        let bridge = Bridge::open(folder.path().to_str().unwrap().into(), key(), app.clone()).unwrap();
        (folder, app, bridge)
    }

    // A second heard, then stopped: the meeting the recorder made, once its audio is whole.
    fn recorded(bridge: &Bridge, app: &App) -> String {
        bridge.apply(json!({"type": "start", "tracks": 1, "offsetMinutes": 60}).to_string()).unwrap();
        bridge.hear(0, sound(1_000, 5_000.0, 440.0));
        bridge.apply(json!({"type": "stop"}).to_string()).unwrap();
        bridge.session.settled();
        assert_eq!(app.last("recorder")["state"], "idle");
        app.last("changed")["meeting"]["id"].as_str().unwrap().to_owned()
    }

    #[test]
    fn a_meeting_is_heard_transcribed_corrected_played_back_and_deleted_in_text() {
        let (folder, app, bridge) = opened();
        let meeting = recorded(&bridge, &app);
        let lines = json!([{"atMs": 0, "text": "Bonjour à tous."}]);
        let minutes = json!({"type": "failed"});
        let transcribed = json!({"type": "transcribed", "meeting": meeting, "transcript": lines, "minutes": minutes});
        bridge.apply(transcribed.to_string()).unwrap();
        let rename = json!({"type": "edit", "meeting": meeting, "edit": {"type": "rename", "title": "Point"}});
        bridge.apply(rename.to_string()).unwrap();
        let whole: Value = serde_json::from_str(&bridge.meeting(meeting.clone()).unwrap()).unwrap();
        let read = (&whole["title"], &whole["durationMs"], &whole["transcript"], &whole["minutes"]["type"]);
        assert_eq!(read, (&json!("Point"), &json!(1_000), &lines, &json!("failed")));
        let changed = app.last("changed");
        // The meeting an event tells has the shape of the one read back, without its lines.
        assert_eq!((&changed["meeting"]["title"], &changed["meeting"]["transcript"]), (&whole["title"], &Value::Null));
        let played = bridge.sound(meeting.clone(), 0, 1_000).unwrap();
        assert_eq!(played.len(), 32_000);
        assert!(played.chunks(2).any(|sample| i16::from_le_bytes([sample[0], sample[1]]).abs() > 1_000));
        bridge.apply(json!({"type": "delete", "meetings": [meeting]}).to_string()).unwrap();
        assert_eq!(app.told().last().map(String::as_str), Some("removed"));
        assert_eq!(bridge.meeting(meeting.clone()), Err(BridgeError::NotFound));
        assert_eq!(bridge.sound(meeting, 0, 1_000), Err(BridgeError::NotFound));
        assert!(fs::read_dir(folder.path()).unwrap().all(|entry| {
            let name = entry.unwrap().file_name().into_string().unwrap();
            !name.ends_with(".notitia") && !name.ends_with(".audio")
        }));
    }

    #[test]
    fn what_the_core_cannot_do_is_an_error() {
        let (folder, app, bridge) = opened();
        for nobody in ["recorded-1", "../recorded-9", ""] {
            assert_eq!(bridge.meeting(nobody.into()), Err(BridgeError::NotFound));
            assert_eq!(bridge.sound(nobody.into(), 0, 1_000), Err(BridgeError::NotFound));
        }
        for text in ["", "{\"type\":\"forget\"}", "{\"type\":\"delete\",\"meetings\":[\"../recorded-9\"]}"] {
            assert_eq!(bridge.apply(text.into()), Err(BridgeError::Invalid));
        }
        assert_eq!(app.told(), ["listed", "engines"]);
        let meeting = recorded(&bridge, &app);
        fs::write(folder.path().join(format!("{meeting}.notitia")), "{\"version\":").unwrap();
        assert_eq!(bridge.meeting(meeting), Err(BridgeError::Unreadable));
        let taken = folder.path().join("device").join("meetings");
        let closed = Bridge::open(taken.to_str().unwrap().into(), key(), Arc::new(App::default()));
        assert_eq!(closed.err(), Some(BridgeError::Unreadable));
        let elsewhere = folder.path().join("elsewhere").to_str().unwrap().to_owned();
        for wrong in [vec![], vec![7; 31], vec![7; 33]] {
            let refused = Bridge::open(elsewhere.clone(), Some(wrong), Arc::new(App::default()));
            assert_eq!(refused.err(), Some(BridgeError::Invalid));
        }
    }

    #[test]
    fn without_the_key_the_meetings_open_and_nothing_is_recorded() {
        let folder = tempfile::tempdir().unwrap();
        let app = Arc::new(App::default());
        let bridge = Bridge::open(folder.path().to_str().unwrap().into(), None, app.clone()).unwrap();
        bridge.apply(json!({"type": "start", "tracks": 1, "offsetMinutes": 60}).to_string()).unwrap();
        bridge.hear(0, sound(1_000, 5_000.0, 440.0));
        bridge.session.settled();
        assert_eq!(app.last("recorder"), json!({"type": "recorder", "state": "failed", "problem": "storage"}));
        assert_eq!(app.told(), ["listed", "engines", "recorder"]);
    }

    // What an app might send by mistake, a byte at a time: an error, never a panic through the bridge.
    #[test]
    fn any_text_or_sound_is_refused_or_heard_without_a_panic() {
        let (_folder, app, bridge) = opened();
        bridge.apply(json!({"type": "start", "tracks": 2, "offsetMinutes": 60}).to_string()).unwrap();
        let mut drawn: u64 = 0x9E37_79B9_7F4A_7C15;
        let mut next = move || {
            drawn ^= drawn << 13;
            drawn ^= drawn >> 7;
            drawn ^= drawn << 17;
            drawn
        };
        for _ in 0..500 {
            let length = (next() % 64) as usize;
            let bytes: Vec<u8> = (0..length).map(|_| next() as u8).collect();
            let _ = bridge.apply(String::from_utf8_lossy(&bytes).into_owned());
            bridge.hear(next() as u8, bytes);
        }
        bridge.session.settled();
        // A panic on the recorder's thread would have failed the recording.
        assert_ne!(app.last("recorder")["state"], "failed");
    }
}
