use serde::de::Error as _;
use serde::{Deserialize, Deserializer};
use time::{Date, UtcDateTime};

use crate::meeting::{self, ActionId, Extra, MeetingId, civil_date, instant};

// The file as the Kotlin app wrote it, with the defaults Kotlin left out. Read, never written: a meeting read
// here is written back as the current version.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Meeting {
    id: MeetingId,
    #[serde(with = "instant")]
    started_at: UtcDateTime,
    duration: Milliseconds,
    #[serde(with = "instant")]
    modified_at: UtcDateTime,
    device: String,
    title: Option<String>,
    #[serde(default)]
    speakers: Vec<String>,
    #[serde(default)]
    transcript: Vec<Line>,
    minutes: MinutesState,
}

#[derive(Deserialize)]
struct Line {
    at: Milliseconds,
    text: Option<String>,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum MinutesState {
    Writing,
    Written { minutes: Minutes },
    Failed,
    Unavailable,
}

#[derive(Deserialize)]
struct Minutes {
    summary: String,
    decisions: Vec<String>,
    actions: Vec<Action>,
}

#[derive(Deserialize)]
struct Action {
    text: String,
    owner: Option<String>,
    #[serde(default, with = "civil_date")]
    due: Option<Date>,
    done: bool,
    source: Option<Source>,
    #[serde(default)]
    reminder: bool,
}

#[derive(Deserialize)]
struct Source {
    quote: String,
    speaker: Option<String>,
    at: Milliseconds,
}

struct Milliseconds(u64);

impl<'de> Deserialize<'de> for Milliseconds {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        milliseconds(&String::deserialize(deserializer)?).map(Self).ok_or_else(|| D::Error::custom("not a duration"))
    }
}

// Kotlin wrote a duration as PT1H5M4.062500S: hours, minutes and seconds in that order, each left out when it
// is zero, and only the seconds with a fraction. What is finer than a millisecond is dropped.
fn milliseconds(text: &str) -> Option<u64> {
    let mut rest = text.strip_prefix("PT")?;
    let mut total: Option<u64> = None;
    for (unit, length) in [('H', 3_600_000), ('M', 60_000), ('S', 1_000)] {
        let Some((number, after)) = rest.split_once(unit) else { continue };
        let (whole, thousandths) = match number.split_once('.') {
            Some((whole, fraction)) if unit == 'S' => {
                digits(fraction)?;
                (whole, digits(&format!("{fraction:0<3.3}"))?)
            }
            Some(_) => return None,
            None => (number, 0),
        };
        let part = digits(whole)?.checked_mul(length)?.checked_add(thousandths)?;
        total = Some(total.unwrap_or(0).checked_add(part)?);
        rest = after;
    }
    total.filter(|_| rest.is_empty())
}

// parse alone would take a sign.
fn digits(text: &str) -> Option<u64> {
    if text.is_empty() || !text.bytes().all(|byte| byte.is_ascii_digit()) { None } else { text.parse().ok() }
}

impl From<Meeting> for meeting::Meeting {
    fn from(file: Meeting) -> Self {
        Self {
            id: file.id,
            started_at: file.started_at,
            offset_minutes: None,
            duration_ms: file.duration.0,
            transcribed_ms: None,
            audio_erased_at: None,
            modified_at: file.modified_at,
            device: file.device,
            title: file.title,
            speakers: file.speakers,
            minutes: file.minutes.into(),
            transcript: file.transcript.into_iter().map(meeting::TranscriptLine::from).collect(),
            extra: Extra::new(),
        }
    }
}

impl From<Line> for meeting::TranscriptLine {
    fn from(line: Line) -> Self {
        Self { at_ms: line.at.0, text: line.text, speaker: None, extra: Extra::new() }
    }
}

impl From<MinutesState> for meeting::MinutesState {
    fn from(state: MinutesState) -> Self {
        match state {
            MinutesState::Writing => Self::Writing,
            MinutesState::Failed => Self::Failed,
            MinutesState::Unavailable => Self::Unavailable,
            MinutesState::Written { minutes } => Self::Written {
                minutes: meeting::Minutes {
                    summary: minutes.summary,
                    decisions: minutes.decisions,
                    actions: minutes.actions.into_iter().enumerate().map(action).collect(),
                    extra: Extra::new(),
                },
            },
        }
    }
}

fn action((rank, action): (usize, Action)) -> meeting::Action {
    meeting::Action {
        id: ActionId::of_rank(rank),
        text: action.text,
        owner: action.owner,
        due: action.due,
        done: action.done,
        reminder: action.reminder,
        source: action.source.map(|source| meeting::ActionSource {
            quote: source.quote,
            speaker: source.speaker,
            at_ms: source.at.0,
            extra: Extra::new(),
        }),
        extra: Extra::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_durations_kotlin_wrote_are_read() {
        assert_eq!(milliseconds("PT0S"), Some(0));
        assert_eq!(milliseconds("PT42M"), Some(2_520_000));
        assert_eq!(milliseconds("PT12M4S"), Some(724_000));
        assert_eq!(milliseconds("PT1H30M"), Some(5_400_000));
        assert_eq!(milliseconds("PT1.500S"), Some(1_500));
        assert_eq!(milliseconds("PT0.5S"), Some(500));
        assert_eq!(milliseconds("PT1H5M4.062500S"), Some(3_904_062));
        assert_eq!(milliseconds("PT48H"), Some(172_800_000));
    }

    #[test]
    fn what_is_not_a_duration_of_kotlin_is_refused() {
        // The last one is how Kotlin writes a duration without end.
        for text in [
            "",
            "PT",
            "42M",
            "PT5",
            "PT5M1H",
            "PT1.5M",
            "PT.5S",
            "PT1.S",
            "-PT1S",
            "PT+1S",
            "PT1S ",
            "PT9999999999999H",
        ] {
            assert_eq!(milliseconds(text), None, "{text}");
        }
    }
}
