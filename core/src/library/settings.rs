use serde::{Deserialize, Serialize};

use crate::meeting::{Extra, KeepAudio};

// Raised when a change would make an older app misread a newer file, as for a meeting. A field that is only added
// does not raise it: an older app keeps what it does not know.
const VERSION: u32 = 1;

// What the user chose of the rules of the core, the same for every meeting. docs/adr/0016-reglages-du-coeur.md.
#[derive(Clone, Default, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(rename_all = "camelCase")]
pub(super) struct Settings {
    #[serde(default)]
    pub keep_audio: KeepAudio,
    // What a newer app keeps here and this one does not know: written back as it was.
    #[serde(flatten)]
    pub extra: Extra,
}

// What an app asks to change. A field left out stays as it is, so a setting added later needs no other request.
#[derive(Default, Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(rename_all = "camelCase")]
pub struct Choice {
    pub keep_audio: Option<KeepAudio>,
}

// The version is read before the settings it wraps: a newer file may give a field another meaning.
#[derive(Deserialize)]
struct Version {
    version: u32,
}

#[derive(Serialize, Deserialize)]
struct File<T> {
    version: u32,
    #[serde(flatten)]
    settings: T,
}

// None for a file damaged or of another version: the choices of a launch with no file then hold.
pub(super) fn decode(bytes: &[u8]) -> Option<Settings> {
    let Version { version } = serde_json::from_slice(bytes).ok()?;
    if version != VERSION {
        return None;
    }
    let file: File<Settings> = serde_json::from_slice(bytes).ok()?;
    Some(file.settings)
}

pub(super) fn encode(settings: &Settings) -> Option<Vec<u8>> {
    serde_json::to_vec(&File { version: VERSION, settings }).ok()
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use super::*;
    use crate::testing::each_damaged;

    // As the first app to keep a choice wrote it.
    const V1: &[u8] = include_bytes!("../../tests/fixtures/settings-v1.json");

    fn read(value: Value) -> Option<Settings> {
        decode(value.to_string().as_bytes())
    }

    #[test]
    fn the_witness_of_the_first_version_is_read_and_written_back_to_the_byte() {
        let read = decode(V1).unwrap();
        assert_eq!(encode(&read).unwrap(), V1);
        assert_eq!((read.keep_audio, read.extra), (KeepAudio::Week, Extra::new()));
    }

    #[test]
    fn what_a_newer_app_added_is_written_back_as_it_was() {
        let newer = json!({"version": 1, "keepAudio": "day", "reminderHour": {"hour": 9}});
        let read = read(newer.clone()).unwrap();
        assert_eq!(read.keep_audio, KeepAudio::Day);
        let written: Value = serde_json::from_slice(&encode(&read).unwrap()).unwrap();
        assert_eq!(written, newer);
    }

    #[test]
    fn the_version_is_written_first() {
        let written = encode(&Settings { keep_audio: KeepAudio::UntilHeard, extra: Extra::new() }).unwrap();
        assert_eq!(written, br#"{"version":1,"keepAudio":"untilHeard"}"#);
    }

    #[test]
    fn a_file_without_a_choice_keeps_the_audio_a_month() {
        assert_eq!(read(json!({"version": 1})).unwrap().keep_audio, KeepAudio::Month);
    }

    #[test]
    fn a_file_damaged_or_of_another_version_is_not_read() {
        for unread in [
            json!({"version": 2, "keepAudio": "day"}),
            json!({"version": 0, "keepAudio": "day"}),
            json!({"keepAudio": "day"}),
            json!({"version": 1, "keepAudio": "fortnight"}),
            json!({"version": 1, "keepAudio": null}),
            json!({"version": "1", "keepAudio": "day"}),
            json!(["version", 1]),
        ] {
            assert_eq!(read(unread.clone()), None, "{unread}");
        }
        for bytes in [&b""[..], b"{\"version\":1,", b"\xff\xfe"] {
            assert_eq!(decode(bytes), None);
        }
    }

    #[test]
    fn damaged_settings_are_refused_or_read_without_a_panic() {
        each_damaged(&[V1], 5_000, |bytes| {
            if let Some(read) = decode(bytes) {
                assert!(encode(&read).is_some_and(|written| decode(&written) == Some(read)));
            }
        });
    }
}
