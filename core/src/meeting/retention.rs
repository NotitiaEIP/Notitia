use time::{Duration, UtcDateTime};

// How many days the audio of a meeting is kept (product rule 5), its lines and minutes kept for good. Written here
// alone, so that no app repeats it. docs/adr/0015-audio-30-jours.md.
pub(crate) const KEPT_DAYS: i64 = 30;

// Begun KEPT_DAYS ago or more. Told by the time between the two instants, which never overflows, as adding days to a
// date of the year 9999 would.
pub(crate) fn expired(started_at: UtcDateTime, now: UtcDateTime) -> bool {
    now - started_at >= Duration::days(KEPT_DAYS)
}

#[cfg(test)]
mod tests {
    use time::macros::utc_datetime;

    use super::*;

    const STARTED: UtcDateTime = utc_datetime!(2026-11-10 9:30);

    #[test]
    fn the_audio_is_kept_30_days() {
        assert!(!expired(STARTED, STARTED + Duration::days(30) - Duration::seconds(1)));
        assert!(expired(STARTED, STARTED + Duration::days(30)));
        assert!(expired(STARTED, STARTED + Duration::days(400)));
    }

    #[test]
    fn a_clock_set_before_the_meeting_keeps_it() {
        assert!(!expired(STARTED, STARTED - Duration::days(400)));
    }

    #[test]
    fn the_first_and_the_last_years_the_format_writes_do_not_panic() {
        let (first, last) = (utc_datetime!(0000-01-01 0:00), utc_datetime!(9999-12-31 23:59:59));
        assert!(expired(first, last));
        assert!(!expired(last, first));
        assert!(!expired(last, last));
    }
}
