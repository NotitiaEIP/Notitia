use std::fmt;
use std::fs;

use serde::Deserialize;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

// Raised when a change would make an older app misread a newer file.
const FORMAT_VERSION: u32 = 1;

#[derive(Debug, PartialEq, uniffi::Record)]
pub struct MeetingSummary {
    pub id: String,
    // None until the user names the meeting.
    pub title: Option<String>,
    // Milliseconds since 1970, UTC: a number crosses every bridge unchanged, a date type does not.
    pub started_at_ms: i64,
}

// Carries no text of the file: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq, uniffi::Error)]
pub enum CoreError {
    Unreadable,
    NotAMeeting,
    // Written by a newer app: the file stays on disk, an update will read it.
    NewerFormat,
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            CoreError::Unreadable => "the file cannot be read",
            CoreError::NotAMeeting => "the file is not a meeting",
            CoreError::NewerFormat => "the file comes from a newer app",
        })
    }
}

impl std::error::Error for CoreError {}

#[derive(Deserialize)]
struct Envelope {
    version: u32,
}

#[derive(Deserialize)]
struct MeetingFile {
    meeting: MeetingHead,
}

// Only what the summary needs: serde skips the other fields, and those a newer app of the same version adds.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MeetingHead {
    id: String,
    title: Option<String>,
    started_at: String,
}

#[uniffi::export]
pub fn read_meeting_summary(path: String) -> Result<MeetingSummary, CoreError> {
    let bytes = fs::read(path).map_err(|_| CoreError::Unreadable)?;
    summary_of(&bytes)
}

// The version is read first, alone: a newer file may have moved every other field.
pub(crate) fn summary_of(bytes: &[u8]) -> Result<MeetingSummary, CoreError> {
    let envelope: Envelope = serde_json::from_slice(bytes).map_err(|_| CoreError::NotAMeeting)?;
    if envelope.version > FORMAT_VERSION {
        return Err(CoreError::NewerFormat);
    }
    let file: MeetingFile = serde_json::from_slice(bytes).map_err(|_| CoreError::NotAMeeting)?;
    let started_at = OffsetDateTime::parse(&file.meeting.started_at, &Rfc3339).map_err(|_| CoreError::NotAMeeting)?;
    let started_at_ms = i64::try_from(started_at.unix_timestamp_nanos() / 1_000_000).map_err(|_| CoreError::NotAMeeting)?;
    Ok(MeetingSummary { id: file.meeting.id, title: file.meeting.title, started_at_ms })
}

#[cfg(test)]
mod tests {
    use super::*;

    // Written by the Kotlin MeetingCodec of the app, version 1: the file every phone holds today.
    const WRITTEN_BY_KOTLIN: &str = include_str!("../tests/fixtures/meeting-v1.notitia");

    #[test]
    fn a_meeting_written_by_the_kotlin_app_is_read() {
        let summary = summary_of(WRITTEN_BY_KOTLIN.as_bytes()).unwrap();
        assert_eq!(
            summary,
            MeetingSummary {
                id: "recorded-1".into(),
                title: Some("Revue de sprint".into()),
                // 2026-11-10T09:30:00Z
                started_at_ms: 1_794_303_000_000,
            }
        );
    }

    #[test]
    fn a_meeting_without_a_title_is_read() {
        let untitled = WRITTEN_BY_KOTLIN.replace("\"title\":\"Revue de sprint\",", "");
        assert_eq!(summary_of(untitled.as_bytes()).unwrap().title, None);
    }

    #[test]
    fn a_file_from_a_newer_format_is_not_read() {
        let newer = WRITTEN_BY_KOTLIN.replace("\"version\":1", "\"version\":2");
        assert_eq!(summary_of(newer.as_bytes()), Err(CoreError::NewerFormat));
    }

    #[test]
    fn a_field_added_by_a_newer_app_is_skipped() {
        let with_extra = WRITTEN_BY_KOTLIN.replacen('{', "{\"color\":\"violet\",", 1);
        assert_eq!(summary_of(with_extra.as_bytes()), summary_of(WRITTEN_BY_KOTLIN.as_bytes()));
    }

    #[test]
    fn a_damaged_file_is_not_read() {
        let half = &WRITTEN_BY_KOTLIN.as_bytes()[..WRITTEN_BY_KOTLIN.len() / 2];
        assert_eq!(summary_of(half), Err(CoreError::NotAMeeting));
        assert_eq!(summary_of(b"[]"), Err(CoreError::NotAMeeting));
        assert_eq!(summary_of(b""), Err(CoreError::NotAMeeting));
    }

    #[test]
    fn a_missing_file_is_unreadable() {
        assert_eq!(read_meeting_summary("/nowhere/meeting.notitia".into()), Err(CoreError::Unreadable));
    }
}
