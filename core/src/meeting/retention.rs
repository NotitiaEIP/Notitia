use serde::{Deserialize, Serialize};
use time::{Duration, UtcDateTime};

use super::{Meeting, MinutesState};

// No audio is kept longer than this, heard or not (product rule 5), its lines and minutes kept for good. Written here
// alone, so that no app repeats it. docs/adr/0015-audio-30-jours.md, docs/adr/0016-reglages-du-coeur.md.
pub(crate) const KEPT_DAYS: i64 = 30;

// How long the user keeps the audio of a meeting once it is heard whole, the shortest first. A meeting not heard yet
// keeps its audio up to the longest: a model still on its way loses no meeting.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum KeepAudio {
    UntilHeard,
    Day,
    Week,
    #[default]
    Month,
}

impl KeepAudio {
    fn days(self) -> i64 {
        match self {
            KeepAudio::UntilHeard => 0,
            KeepAudio::Day => 1,
            KeepAudio::Week => 7,
            KeepAudio::Month => KEPT_DAYS,
        }
    }
}

// Why the audio of a meeting is due.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Due {
    // Past the longest choice, whatever was heard of it.
    Aged,
    // Heard whole, and past the choice of the user. Due only if it was heard indeed: see Meeting::never_heard.
    Heard,
}

// Told by the time since the meeting began, which never overflows, as adding days to a date of the year 9999 would.
// Until heard, whatever that time: a clock set before the meeting does not keep an audio the user no longer wants.
pub(crate) fn due(meeting: &Meeting, now: UtcDateTime, keep: KeepAudio) -> Option<Due> {
    let age = now - meeting.started_at;
    if age >= Duration::days(KEPT_DAYS) {
        return Some(Due::Aged);
    }
    let past = keep == KeepAudio::UntilHeard || age >= Duration::days(keep.days());
    (meeting.transcribed_ms.is_none() && past).then_some(Due::Heard)
}

impl Meeting {
    // No edge left to hear from, yet not a line although it lasts, its audio still there: an app before the core's, or
    // one without an engine, left it so. Heard from its start before its minutes are written (src/pipeline/write.rs),
    // its audio kept meanwhile as that of any meeting not heard yet. Minutes written say it was heard: nothing was said.
    pub(crate) fn never_heard(&self) -> bool {
        self.transcribed_ms.is_none()
            && self.transcript.is_empty()
            && self.duration_ms > 0
            && self.audio_erased_at.is_none()
            && !matches!(self.minutes, MinutesState::Written { .. })
    }
}

#[cfg(test)]
mod tests {
    use time::macros::utc_datetime;

    use super::*;
    use crate::meeting::testing::{heard, line, meeting};

    const STARTED: UtcDateTime = utc_datetime!(2026-11-10 9:30);
    const CHOICES: [KeepAudio; 4] = [KeepAudio::UntilHeard, KeepAudio::Day, KeepAudio::Week, KeepAudio::Month];
    const SECOND: Duration = Duration::seconds(1);

    fn begun(started_at: UtcDateTime, transcribed_ms: Option<u64>) -> Meeting {
        Meeting { started_at, transcribed_ms, ..heard("recorded-1") }
    }

    fn whole() -> Meeting {
        begun(STARTED, None)
    }

    // Heard up to its tenth minute: being heard, or waiting for a model.
    fn unheard() -> Meeting {
        begun(STARTED, Some(600_000))
    }

    fn after(meeting: &Meeting, age: Duration, keep: KeepAudio) -> Option<Due> {
        due(meeting, STARTED + age, keep)
    }

    #[test]
    fn a_meeting_heard_whole_keeps_its_audio_as_long_as_the_user_chose() {
        for (keep, days) in [(KeepAudio::Day, 1), (KeepAudio::Week, 7)] {
            let kept = Duration::days(days);
            assert_eq!(after(&whole(), kept - SECOND, keep), None, "{keep:?}");
            assert_eq!(after(&whole(), kept, keep), Some(Due::Heard), "{keep:?}");
        }
        assert_eq!(after(&whole(), Duration::days(30) - SECOND, KeepAudio::Month), None);
    }

    #[test]
    fn until_heard_the_audio_of_a_meeting_heard_whole_goes_at_once_whatever_the_clock() {
        for age in [Duration::ZERO, -Duration::days(400), Duration::days(29)] {
            assert_eq!(after(&whole(), age, KeepAudio::UntilHeard), Some(Due::Heard), "{age}");
        }
    }

    #[test]
    fn a_meeting_not_heard_whole_keeps_its_audio_30_days_whatever_the_choice() {
        for keep in CHOICES {
            assert_eq!(after(&unheard(), Duration::days(30) - SECOND, keep), None, "{keep:?}");
            assert_eq!(after(&unheard(), Duration::days(30), keep), Some(Due::Aged), "{keep:?}");
        }
    }

    #[test]
    fn no_audio_is_kept_past_30_days() {
        for (meeting, keep) in CHOICES.into_iter().flat_map(|keep| [(whole(), keep), (unheard(), keep)]) {
            assert_eq!(after(&meeting, Duration::days(30), keep), Some(Due::Aged));
            assert_eq!(after(&meeting, Duration::days(400), keep), Some(Due::Aged));
        }
    }

    #[test]
    fn a_clock_set_before_the_meeting_keeps_it() {
        for keep in [KeepAudio::Day, KeepAudio::Week, KeepAudio::Month] {
            assert_eq!(after(&whole(), -Duration::days(400), keep), None, "{keep:?}");
        }
        for keep in CHOICES {
            assert_eq!(after(&unheard(), -Duration::days(400), keep), None, "{keep:?}");
        }
    }

    #[test]
    fn the_choices_go_from_the_shortest_to_the_longest_and_a_month_is_the_default() {
        assert!(CHOICES.windows(2).all(|pair| pair[0] < pair[1]));
        assert_eq!(KeepAudio::default(), KeepAudio::Month);
    }

    #[test]
    fn the_first_and_the_last_years_the_format_writes_do_not_panic() {
        let (first, last) = (utc_datetime!(0000-01-01 0:00), utc_datetime!(9999-12-31 23:59:59));
        for keep in CHOICES {
            for transcribed_ms in [None, Some(0)] {
                let until_heard = (keep == KeepAudio::UntilHeard && transcribed_ms.is_none()).then_some(Due::Heard);
                assert_eq!(due(&begun(first, transcribed_ms), last, keep), Some(Due::Aged));
                assert_eq!(due(&begun(last, transcribed_ms), first, keep), until_heard);
                assert_eq!(due(&begun(last, transcribed_ms), last, keep), until_heard);
            }
        }
    }

    // As the core recorded it before it heard meetings itself: whole, its minutes owed, not a line yet.
    fn left_unheard() -> Meeting {
        Meeting { minutes: MinutesState::Writing, transcript: Vec::new(), ..meeting("recorded-1") }
    }

    #[test]
    fn a_meeting_that_lasts_without_a_line_nor_minutes_was_never_heard() {
        assert!(left_unheard().never_heard());
        assert!(Meeting { minutes: MinutesState::Failed, ..left_unheard() }.never_heard());
        assert!(Meeting { minutes: MinutesState::Unavailable, ..left_unheard() }.never_heard());
    }

    #[test]
    fn a_line_minutes_written_an_edge_no_sound_or_an_audio_gone_say_otherwise() {
        let heard = [
            Meeting { transcript: vec![line(0, "Bonjour à tous.")], ..left_unheard() },
            // Heard, nothing said.
            Meeting { minutes: meeting("recorded-1").minutes, ..left_unheard() },
            // Still to be heard from where it was.
            Meeting { transcribed_ms: Some(0), ..left_unheard() },
            Meeting { duration_ms: 0, ..left_unheard() },
            Meeting { audio_erased_at: Some(STARTED), ..left_unheard() },
        ];
        for meeting in heard {
            assert!(!meeting.never_heard(), "{:?}", (meeting.transcribed_ms, meeting.duration_ms, meeting.minutes));
        }
    }
}
