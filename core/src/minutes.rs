use time::{Date, UtcOffset};

use crate::meeting::Meeting;

mod draft;
mod prompt;
mod reply;

pub use draft::{Draft, Step};

// The minutes of a meeting, written by a text engine from its transcript: what it is asked, how its answer is read,
// and how an hour no model reads at once is cut into slices, then merged. No thread and no disk: the pipeline calls
// it. docs/adr/0013-compte-rendu.md.

// One line per item rather than JSON: the open models break JSON more often than lines.
const SUMMARY: &str = "SUMMARY:";
const DECISION: &str = "DECISION:";
const ACTION: &str = "ACTION:";
const NONE: &str = "-";

// The day on the calendar where it was recorded, so that « vendredi » becomes a date. In UTC for a meeting recorded
// before the core kept the offset, or for an offset no place has.
pub fn day(meeting: &Meeting) -> Date {
    let offset = meeting.offset_minutes.and_then(|minutes| UtcOffset::from_whole_seconds(i32::from(minutes) * 60).ok());
    let local = offset.and_then(|offset| meeting.started_at.checked_to_offset(offset));
    local.map_or(meeting.started_at.date(), |local| local.date())
}

#[cfg(test)]
mod tests {
    use time::macros::{date, utc_datetime};

    use super::*;
    use crate::meeting::testing::meeting;

    #[test]
    fn the_day_of_a_meeting_is_the_one_where_it_was_recorded() {
        let late = |offset_minutes| Meeting {
            started_at: utc_datetime!(2026-11-06 23:30),
            offset_minutes,
            ..meeting("recorded-1")
        };
        assert_eq!(day(&late(Some(60))), date!(2026 - 11 - 07));
        assert_eq!(day(&late(Some(-600))), date!(2026 - 11 - 06));
        assert_eq!(day(&late(None)), date!(2026 - 11 - 06));
        // No place is a day ahead: the day stays the one of UTC, and nothing panics.
        assert_eq!(day(&late(Some(i16::MAX))), date!(2026 - 11 - 06));
        assert_eq!(day(&late(Some(i16::MIN))), date!(2026 - 11 - 06));
        let last =
            Meeting { started_at: utc_datetime!(9999-12-31 23:30), offset_minutes: Some(60), ..meeting("recorded-1") };
        assert_eq!(day(&last), date!(9999 - 12 - 31));
    }
}
