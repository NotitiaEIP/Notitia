use std::fmt;
use std::sync::Arc;

use crate::library::{Event, Library, LibraryError, Listener};
use crate::meeting::MeetingId;

// The core as an app reaches it, and the only place that speaks to UniFFI. Text goes in and text comes out, the
// JSON of Change, Event and Meeting: a new change or a new event touches neither RustCore.kt nor RustCore.swift,
// which are written by hand and, for Swift, compiled by the CI alone.
#[derive(uniffi::Object)]
pub struct Bridge {
    library: Library,
}

// Held by the app. Called on the thread that asked for the change, before its file is written: it may read a
// meeting back, it must not ask for a change, which would wait for itself.
#[uniffi::export(with_foreign)]
pub trait BridgeListener: Send + Sync {
    fn on_event(&self, event: String);
}

// Carries no text of a meeting: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq, uniffi::Error)]
pub enum BridgeError {
    NotFound,
    // The folder or the file of the meeting.
    Unreadable,
    // The text is not a change this core knows: a fault of the app, never of its user.
    Invalid,
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            BridgeError::NotFound => "no meeting has this identifier",
            BridgeError::Unreadable => "the meetings cannot be read",
            BridgeError::Invalid => "the text is not a change",
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

struct Told(Arc<dyn BridgeListener>);

impl Listener for Told {
    fn on_event(&self, event: &Event) {
        // Fails only on a date outside the years 0 to 9999, which neither a file nor a change can bring in.
        if let Ok(event) = serde_json::to_string(event) {
            self.0.on_event(event);
        }
    }
}

#[uniffi::export]
impl Bridge {
    #[uniffi::constructor]
    pub fn open(directory: String, listener: Arc<dyn BridgeListener>) -> Result<Self, BridgeError> {
        // No key yet, so no audio: T-58 brings the key of the app across.
        Ok(Self { library: Library::open(directory, None, Arc::new(Told(listener)))? })
    }

    // The whole meeting, lines included, as its file holds it.
    pub fn meeting(&self, id: String) -> Result<String, BridgeError> {
        // An identifier that cannot name a file names no meeting.
        let id = MeetingId::new(id).map_err(|_| BridgeError::NotFound)?;
        serde_json::to_string(&self.library.meeting(&id)?).map_err(|_| BridgeError::Unreadable)
    }

    // Says nothing back but a text it cannot read: what changed, and a file that could not be written, come as
    // events.
    pub fn apply(&self, change: String) -> Result<(), BridgeError> {
        self.library.apply(serde_json::from_str(&change).map_err(|_| BridgeError::Invalid)?);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Mutex;

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
    }

    fn opened() -> (TempDir, Arc<App>, Bridge) {
        let folder = tempfile::tempdir().unwrap();
        let app = Arc::new(App::default());
        let bridge = Bridge::open(folder.path().to_str().unwrap().into(), app.clone()).unwrap();
        (folder, app, bridge)
    }

    fn record(bridge: &Bridge) {
        let heard = json!({
            "type": "record",
            "id": "recorded-9",
            "startedAt": "2026-11-11T07:00:00Z",
            "durationMs": 60_000,
            "transcript": [{"atMs": 0, "text": "Bonjour à tous."}],
            "minutes": {"type": "unavailable"},
        });
        bridge.apply(heard.to_string()).unwrap();
    }

    #[test]
    fn a_meeting_is_recorded_corrected_read_and_deleted_in_text() {
        let (_folder, app, bridge) = opened();
        record(&bridge);
        let rename = json!({"type": "edit", "meeting": "recorded-9", "edit": {"type": "rename", "title": "Point"}});
        bridge.apply(rename.to_string()).unwrap();
        let whole: Value = serde_json::from_str(&bridge.meeting("recorded-9".into()).unwrap()).unwrap();
        assert_eq!((&whole["title"], &whole["transcript"][0]["text"]), (&json!("Point"), &json!("Bonjour à tous.")));
        let changed = app.0.lock().unwrap().last().cloned().unwrap();
        // The meeting an event tells has the shape of the one read back, without its lines.
        assert_eq!((&changed["meeting"]["title"], &changed["meeting"]["transcript"]), (&whole["title"], &Value::Null));
        bridge.apply(json!({"type": "delete", "meetings": ["recorded-9"]}).to_string()).unwrap();
        assert_eq!(app.told(), ["listed", "changed", "changed", "removed"]);
        assert_eq!(bridge.meeting("recorded-9".into()), Err(BridgeError::NotFound));
    }

    #[test]
    fn what_the_core_cannot_do_is_an_error() {
        let (folder, app, bridge) = opened();
        for nobody in ["recorded-1", "../recorded-9", ""] {
            assert_eq!(bridge.meeting(nobody.into()), Err(BridgeError::NotFound));
        }
        for text in ["", "{\"type\":\"forget\"}", "{\"type\":\"delete\",\"meetings\":[\"../recorded-9\"]}"] {
            assert_eq!(bridge.apply(text.into()), Err(BridgeError::Invalid));
        }
        assert_eq!(app.told(), ["listed"]);
        record(&bridge);
        fs::write(folder.path().join("recorded-9.notitia"), "{\"version\":").unwrap();
        assert_eq!(bridge.meeting("recorded-9".into()), Err(BridgeError::Unreadable));
        let taken = folder.path().join("device").join("meetings");
        let closed = Bridge::open(taken.to_str().unwrap().into(), Arc::new(App::default()));
        assert_eq!(closed.err(), Some(BridgeError::Unreadable));
    }
}
