mod change;
mod id;

use serde::{Deserialize, Serialize};
use time::{Date, UtcDateTime};

pub(crate) use change::Stamp;
pub use change::{ActionDraft, Change, Edit, MinutesDraft};
pub use id::{ActionId, InvalidId, MeetingId};

// What a newer app wrote and this one does not know: kept, so rewriting a file loses nothing of it.
pub type Extra = serde_json::Map<String, serde_json::Value>;

// A day on the calendar, the same in every time zone: an action is due on the 14th wherever the phone is.
// Written by hand: the module that time generates refuses a null inside the minutes, where serde reads twice.
pub(crate) mod civil_date {
    use serde::de::Error as _;
    use serde::ser::Error as _;
    use serde::{Deserialize, Deserializer, Serializer};
    use time::Date;
    use time::format_description::BorrowedFormatItem;
    use time::macros::format_description;

    const FORMAT: &[BorrowedFormatItem<'_>] = format_description!("[year]-[month]-[day]");

    pub fn serialize<S: Serializer>(date: &Option<Date>, serializer: S) -> Result<S::Ok, S::Error> {
        match date {
            Some(date) => serializer.serialize_str(&date.format(FORMAT).map_err(S::Error::custom)?),
            None => serializer.serialize_none(),
        }
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<Date>, D::Error> {
        let text = Option::<String>::deserialize(deserializer)?;
        text.map(|text| Date::parse(&text, FORMAT)).transpose().map_err(D::Error::custom)
    }
}

// Always written in UTC, whatever offset it was read with: every app then writes the same text for an instant.
pub(crate) mod instant {
    use serde::de::Error as _;
    use serde::ser::Error as _;
    use serde::{Deserialize, Deserializer, Serializer};
    use time::UtcDateTime;
    use time::format_description::well_known::Rfc3339;

    pub fn serialize<S: Serializer>(instant: &UtcDateTime, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&instant.format(&Rfc3339).map_err(S::Error::custom)?)
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<UtcDateTime, D::Error> {
        UtcDateTime::parse(&String::deserialize(deserializer)?, &Rfc3339).map_err(D::Error::custom)
    }
}

/// A meeting as the core keeps it, and as version 2 of the `.notitia` format writes it: a field that is absent
/// and a field that is null mean the same.
///
/// Product rule 1, held by the compiler: outside the tests a meeting has no `Debug`, so no log can print one.
///
/// ```
/// fn exists<T>() {}
/// exists::<notitia::meeting::Meeting>();
/// ```
///
/// ```compile_fail
/// fn printable<T: std::fmt::Debug>() {}
/// printable::<notitia::meeting::Meeting>();
/// ```
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(rename_all = "camelCase")]
pub struct Meeting {
    pub id: MeetingId,
    #[serde(with = "instant")]
    pub started_at: UtcDateTime,
    // Ahead of UTC where it was recorded, given by the app: its day on the calendar is then the same on every
    // device. None for a meeting recorded before the core held the recordings.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub offset_minutes: Option<i16>,
    // The audio actually heard, pauses left out.
    pub duration_ms: u64,
    // When and where it last changed, so two devices can be reconciled later (E10).
    #[serde(with = "instant")]
    pub modified_at: UtcDateTime,
    pub device: String,
    // None until the user names the meeting.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub speakers: Vec<String>,
    pub minutes: MinutesState,
    // Last, as the longest: whoever reads a file by eye finds the rest first.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub transcript: Vec<TranscriptLine>,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Meeting {
    // The text an app receives, written here so that no app writes it on its own. None only for a date outside the
    // years 0 to 9999, which neither a file nor a change can bring in.
    pub fn json(&self) -> Option<String> {
        serde_json::to_string(self).ok()
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum MinutesState {
    Writing,
    Written { minutes: Minutes },
    Failed,
    // No writer in this build.
    Unavailable,
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
pub struct Minutes {
    pub summary: String,
    pub decisions: Vec<String>,
    pub actions: Vec<Action>,
    #[serde(flatten)]
    pub extra: Extra,
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
pub struct Action {
    pub id: ActionId,
    pub text: String,
    // A first name, as said in the meeting: None when nobody was named.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner: Option<String>,
    #[serde(default, with = "civil_date", skip_serializing_if = "Option::is_none")]
    pub due: Option<Date>,
    pub done: bool,
    // Asked by the user: a notification the day before the action is due.
    #[serde(default)]
    pub reminder: bool,
    // None when the minutes cannot point to the sentence.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<ActionSource>,
    #[serde(flatten)]
    pub extra: Extra,
}

// The sentence as transcribed, never as the model rewrote it.
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(rename_all = "camelCase")]
pub struct ActionSource {
    pub quote: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub speaker: Option<String>,
    pub at_ms: u64,
    #[serde(flatten)]
    pub extra: Extra,
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(rename_all = "camelCase")]
pub struct TranscriptLine {
    pub at_ms: u64,
    // None for a passage the engine could not transcribe.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    // None until the voices are told apart (E2).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub speaker: Option<String>,
    #[serde(flatten)]
    pub extra: Extra,
}

// The meetings the tests start from, made up.
#[cfg(test)]
pub(crate) mod testing {
    use time::macros::utc_datetime;

    use super::*;

    pub fn id(text: &str) -> MeetingId {
        MeetingId::new(text).unwrap()
    }

    pub fn meeting(text: &str) -> Meeting {
        Meeting {
            id: id(text),
            started_at: utc_datetime!(2026-11-10 9:30),
            offset_minutes: None,
            duration_ms: 30 * 60_000,
            modified_at: utc_datetime!(2026-11-10 10:00),
            device: "phone-1".into(),
            title: None,
            speakers: Vec::new(),
            minutes: written(&["Préparer la démo"]),
            transcript: Vec::new(),
            extra: Extra::new(),
        }
    }

    pub fn written(actions: &[&str]) -> MinutesState {
        let action = |(rank, text): (usize, &&str)| Action {
            id: ActionId::of_rank(rank),
            text: (*text).into(),
            owner: None,
            due: None,
            done: false,
            reminder: false,
            source: None,
            extra: Extra::new(),
        };
        MinutesState::Written {
            minutes: Minutes {
                summary: String::new(),
                decisions: Vec::new(),
                actions: actions.iter().enumerate().map(action).collect(),
                extra: Extra::new(),
            },
        }
    }

    // With a line, as a meeting that was transcribed.
    pub fn heard(text: &str) -> Meeting {
        Meeting { transcript: vec![line(0, "Bonjour à tous.")], ..meeting(text) }
    }

    pub fn line(at_ms: u64, text: &str) -> TranscriptLine {
        TranscriptLine { at_ms, text: Some(text.into()), speaker: None, extra: Extra::new() }
    }
}
