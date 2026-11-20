mod v1;

use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::value::RawValue;

use crate::meeting::Meeting;

// Raised when a change would make an older app misread a newer file. A field that is only added does not raise
// it: an older app keeps what it does not know (see Extra).
pub const VERSION: u32 = 2;

// Carries no text of the file: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq)]
pub enum DecodeError {
    NotAMeeting,
    // Written by a newer app: the file stays on disk, an update will read it.
    NewerFormat,
}

impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            DecodeError::NotAMeeting => "the file is not a meeting",
            DecodeError::NewerFormat => "the file comes from a newer app",
        })
    }
}

impl std::error::Error for DecodeError {}

// The meeting holds a date outside the years 0 to 9999, which the format cannot write.
#[derive(Debug, PartialEq)]
pub struct EncodeError;

impl fmt::Display for EncodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("the meeting cannot be written")
    }
}

impl std::error::Error for EncodeError {}

// The meeting is kept as it stands in the file until the version says how to read it: a newer file may have
// moved every field of it, or the meeting itself.
#[derive(Deserialize)]
struct Read<'a> {
    version: u32,
    #[serde(borrow)]
    meeting: Option<&'a RawValue>,
}

#[derive(Serialize)]
struct Written<'a> {
    version: u32,
    meeting: &'a Meeting,
}

pub fn decode(bytes: &[u8]) -> Result<Meeting, DecodeError> {
    let file: Read = serde_json::from_slice(bytes).map_err(|_| DecodeError::NotAMeeting)?;
    if file.version > VERSION {
        return Err(DecodeError::NewerFormat);
    }
    let meeting = file.meeting.ok_or(DecodeError::NotAMeeting)?.get();
    match file.version {
        VERSION => serde_json::from_str(meeting),
        1 => serde_json::from_str::<v1::Meeting>(meeting).map(Meeting::from),
        _ => return Err(DecodeError::NotAMeeting),
    }
    .map_err(|_| DecodeError::NotAMeeting)
}

pub fn encode(meeting: &Meeting) -> Result<Vec<u8>, EncodeError> {
    serde_json::to_vec(&Written { version: VERSION, meeting }).map_err(|_| EncodeError)
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};
    use time::macros::{date, utc_datetime};

    use super::*;
    use crate::meeting::{Action, ActionId, ActionSource, Extra, MeetingId, Minutes, MinutesState, TranscriptLine};

    // Written by the Kotlin MeetingCodec of the app, version 1: the files a phone holds before the core.
    const BY_KOTLIN: &str = include_str!("../tests/fixtures/meeting-v1.notitia");
    const BY_KOTLIN_RECORDING: &str = include_str!("../tests/fixtures/meeting-v1-recording.notitia");
    const BY_KOTLIN_REMINDER: &str = include_str!("../tests/fixtures/meeting-v1-reminder.notitia");
    // What the core writes for BY_KOTLIN. The other apps read this file in their own tests: a change here is a
    // change of the format.
    const VERSION_2: &str = include_str!("../tests/fixtures/meeting-v2.notitia");

    fn line(at_ms: u64, text: Option<&str>) -> TranscriptLine {
        TranscriptLine { at_ms, text: text.map(String::from), speaker: None, extra: Extra::new() }
    }

    fn sprint_review() -> Meeting {
        Meeting {
            id: MeetingId::new("recorded-1").unwrap(),
            started_at: utc_datetime!(2026-11-10 9:30),
            offset_minutes: None,
            duration_ms: 42 * 60_000,
            modified_at: utc_datetime!(2026-11-10 10:15),
            device: "phone-1".into(),
            title: Some("Revue de sprint".into()),
            speakers: vec!["Lucas".into(), "Nathan".into()],
            minutes: MinutesState::Written {
                minutes: Minutes {
                    summary: "Point sur la démo.".into(),
                    decisions: vec!["La démo a lieu le 15 novembre.".into()],
                    actions: vec![
                        Action {
                            id: ActionId::of_rank(0),
                            text: "Préparer la démo".into(),
                            owner: Some("Lucas".into()),
                            due: Some(date!(2026 - 11 - 14)),
                            done: true,
                            reminder: false,
                            source: Some(ActionSource {
                                quote: "Je prépare la démo.".into(),
                                speaker: None,
                                at_ms: 724_000,
                                extra: Extra::new(),
                            }),
                            extra: Extra::new(),
                        },
                        Action {
                            id: ActionId::of_rank(1),
                            text: "Rédiger le registre RGPD".into(),
                            owner: None,
                            due: None,
                            done: false,
                            reminder: false,
                            source: None,
                            extra: Extra::new(),
                        },
                    ],
                    extra: Extra::new(),
                },
            },
            transcript: vec![line(0, Some("Bonjour à tous.")), line(30_000, None)],
            extra: Extra::new(),
        }
    }

    fn read(text: &str) -> Meeting {
        decode(text.as_bytes()).unwrap()
    }

    fn written(meeting: &Meeting) -> String {
        String::from_utf8(encode(meeting).unwrap()).unwrap()
    }

    #[test]
    fn a_meeting_written_by_the_kotlin_app_is_read() {
        assert_eq!(read(BY_KOTLIN), sprint_review());
    }

    #[test]
    fn what_kotlin_left_out_and_its_odd_durations_are_read() {
        let recording = read(BY_KOTLIN_RECORDING);
        assert_eq!(recording.started_at, utc_datetime!(2026-11-12 14:02:03.25));
        assert_eq!(recording.modified_at, utc_datetime!(2026-11-12 15:10:00.123456789));
        assert_eq!(recording.duration_ms, 3_904_062);
        assert_eq!((recording.title, recording.speakers), (None, vec![]));
        assert_eq!(
            recording.transcript,
            [line(0, Some("On commence par le budget.")), line(1_500, None), line(5_400_000, Some("Merci à tous."))]
        );
        assert_eq!(recording.minutes, MinutesState::Writing);
    }

    #[test]
    fn a_reminder_and_the_speaker_of_a_quote_are_read() {
        let MinutesState::Written { minutes } = read(BY_KOTLIN_REMINDER).minutes else { panic!("not written") };
        let action = &minutes.actions[0];
        assert!(action.reminder);
        assert_eq!(action.due, Some(date!(2026 - 11 - 20)));
        let source = action.source.as_ref().unwrap();
        assert_eq!((source.speaker.as_deref(), source.at_ms), (Some("Lucas"), 12_500));
    }

    #[test]
    fn every_state_of_the_minutes_comes_back() {
        for (name, state) in [
            ("writing", MinutesState::Writing),
            ("failed", MinutesState::Failed),
            ("unavailable", MinutesState::Unavailable),
        ] {
            let by_kotlin = BY_KOTLIN_RECORDING.replace("\"type\":\"writing\"", &format!("\"type\":\"{name}\""));
            assert_eq!(read(&by_kotlin).minutes, state);
            let meeting = Meeting { minutes: state, ..sprint_review() };
            assert_eq!(read(&written(&meeting)), meeting);
        }
    }

    #[test]
    fn a_version_1_meeting_is_written_back_as_version_2() {
        assert_eq!(written(&read(BY_KOTLIN)), VERSION_2);
    }

    #[test]
    fn a_meeting_comes_back_as_it_was_written() {
        assert_eq!(read(VERSION_2), sprint_review());
        for by_kotlin in [BY_KOTLIN_RECORDING, BY_KOTLIN_REMINDER] {
            let meeting = read(by_kotlin);
            assert_eq!(read(&written(&meeting)), meeting);
        }
    }

    #[test]
    fn a_field_left_out_and_a_null_field_mean_the_same() {
        let with_nulls = VERSION_2
            .replace("\"done\":false,", "\"owner\":null,\"due\":null,\"source\":null,\"done\":false,")
            .replace("{\"atMs\":30000}", "{\"atMs\":30000,\"text\":null,\"speaker\":null}");
        assert_ne!(with_nulls, VERSION_2);
        assert_eq!(read(&with_nulls), sprint_review());
    }

    #[test]
    fn the_speaker_of_a_line_comes_back() {
        let mut meeting = sprint_review();
        meeting.transcript[0].speaker = Some("Lucas".into());
        assert_eq!(read(&written(&meeting)), meeting);
    }

    #[test]
    fn the_offset_of_a_recording_is_written_after_its_start() {
        let meeting = Meeting { offset_minutes: Some(-210), ..sprint_review() };
        let text = written(&meeting);
        assert!(text.contains("\"startedAt\":\"2026-11-10T09:30:00Z\",\"offsetMinutes\":-210,"));
        assert_eq!(read(&text), meeting);
    }

    #[test]
    fn a_file_from_a_newer_format_is_not_read() {
        let newer = VERSION_2.replace("\"version\":2", "\"version\":3");
        assert_eq!(decode(newer.as_bytes()), Err(DecodeError::NewerFormat));
        assert_eq!(decode(b"{\"version\":3,\"reunion\":[]}"), Err(DecodeError::NewerFormat));
    }

    #[test]
    fn a_field_added_to_a_version_1_file_is_skipped() {
        let with_more =
            BY_KOTLIN.replacen('{', "{\"color\":\"violet\",", 1).replace("\"done\":", "\"score\":3,\"done\":");
        assert_eq!(read(&with_more), sprint_review());
    }

    #[test]
    fn a_field_added_by_a_newer_app_is_kept_when_the_meeting_is_rewritten() {
        let by_newer_app = VERSION_2
            .replace("\"device\":", "\"place\":{\"room\":\"B12\"},\"device\":")
            .replace("\"summary\":", "\"tone\":\"calm\",\"summary\":")
            .replace("\"done\":true", "\"done\":true,\"priority\":2")
            .replace("\"atMs\":724000", "\"atMs\":724000,\"line\":7")
            .replace("{\"atMs\":0,", "{\"atMs\":0,\"confidence\":0.9,");
        let mut meeting = read(&by_newer_app);
        meeting.title = Some("Point produit".into());
        let rewritten: Value = serde_json::from_str(&written(&meeting)).unwrap();
        let kept = |path: &str| rewritten.pointer(path).cloned();
        assert_eq!(kept("/meeting/title"), Some(json!("Point produit")));
        assert_eq!(kept("/meeting/place"), Some(json!({"room": "B12"})));
        assert_eq!(kept("/meeting/minutes/minutes/tone"), Some(json!("calm")));
        assert_eq!(kept("/meeting/minutes/minutes/actions/0/priority"), Some(json!(2)));
        assert_eq!(kept("/meeting/minutes/minutes/actions/0/source/line"), Some(json!(7)));
        assert_eq!(kept("/meeting/transcript/0/confidence"), Some(json!(0.9)));
    }

    #[test]
    fn a_damaged_file_is_not_read() {
        for file in [BY_KOTLIN, VERSION_2] {
            assert_eq!(decode(&file.as_bytes()[..file.len() / 2]), Err(DecodeError::NotAMeeting));
        }
        for file in ["", "[]", "{}", "{\"version\":2}", "{\"version\":0,\"meeting\":{}}", "{\"version\":-1}"] {
            assert_eq!(decode(file.as_bytes()), Err(DecodeError::NotAMeeting), "{file}");
        }
        let without_version = VERSION_2.replace("\"version\":2,", "");
        assert_eq!(decode(without_version.as_bytes()), Err(DecodeError::NotAMeeting));
    }

    #[test]
    fn an_identifier_that_would_leave_the_folder_is_refused() {
        for file in [BY_KOTLIN, VERSION_2] {
            let escaping = file.replace("\"id\":\"recorded-1\"", "\"id\":\"../recorded-1\"");
            assert_eq!(decode(escaping.as_bytes()), Err(DecodeError::NotAMeeting));
        }
        let action = VERSION_2.replace("\"id\":\"1\"", "\"id\":\"1/2\"");
        assert_eq!(decode(action.as_bytes()), Err(DecodeError::NotAMeeting));
    }

    #[test]
    fn an_instant_read_with_an_offset_is_written_in_utc() {
        let in_paris = VERSION_2.replace("2026-11-10T09:30:00Z", "2026-11-10T10:30:00+01:00");
        assert_eq!(written(&read(&in_paris)), VERSION_2);
    }

    #[test]
    fn an_instant_that_leaves_the_years_0_to_9999_in_utc_is_refused() {
        // The first made the core panic as it opened the folder; the second was read, then could be neither
        // written back nor told to the app.
        for instant in ["9999-12-31T23:59:59-01:00", "0000-01-01T00:00:00+01:00"] {
            let file = VERSION_2.replace("2026-11-10T09:30:00Z", instant);
            assert_eq!(decode(file.as_bytes()), Err(DecodeError::NotAMeeting), "{instant}");
        }
        for instant in ["9999-12-31T23:59:59+01:00", "0000-01-01T00:00:00-01:00", "0000-01-01T00:00:00Z"] {
            let meeting = read(&VERSION_2.replace("2026-11-10T09:30:00Z", instant));
            assert_eq!(read(&written(&meeting)), meeting, "{instant}");
        }
    }

    #[test]
    fn a_date_the_format_cannot_hold_is_not_written() {
        let meeting = Meeting { started_at: utc_datetime!(-0001-01-01 0:00), ..sprint_review() };
        assert_eq!(encode(&meeting), Err(EncodeError));
        // Nor handed to an app.
        assert_eq!(meeting.json(), None);
        assert!(sprint_review().json().is_some());
    }
}
