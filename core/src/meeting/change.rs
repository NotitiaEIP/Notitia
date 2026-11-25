use serde::Deserialize;
use time::{Date, UtcDateTime};

use super::{
    Action, ActionId, ActionSource, Extra, Meeting, MeetingId, Minutes, MinutesState, TranscriptLine, civil_date,
};

// What an app or the recorder asks of the meetings. A value, never a function: it crosses the bridge as text, and
// is read here as it stands. tests/fixtures/bridge/changes.json holds one of each kind, which every app writes in
// its own tests: a change of shape here is a change of the bridge.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Change {
    Edit {
        meeting: MeetingId,
        edit: Edit,
    },
    // For good, its file too.
    Delete {
        meetings: Vec<MeetingId>,
    },
    // Minutes that failed, or that an app older than the core never wrote: owed again, to the next writer.
    Rewrite {
        meeting: MeetingId,
    },
    // Samples: changed like the others for as long as the app runs, never written.
    Lend {
        meetings: Vec<Meeting>,
    },
    // The recorder's own, at its first sound and then as the audio grows: never read from an app.
    #[serde(skip)]
    Begin {
        id: MeetingId,
        started_at: UtcDateTime,
        offset_minutes: i16,
    },
    #[serde(skip)]
    Lasted {
        meeting: MeetingId,
        duration_ms: u64,
    },
    // What the core heard of a meeting, never read from an app: lines to add after the others, from the edge it had
    // reached. Made nothing of unless the meeting is heard up to `from_ms`, so a window is never added twice. `until_ms`
    // None: the transcript is whole.
    #[serde(skip)]
    Heard {
        meeting: MeetingId,
        lines: Vec<TranscriptLine>,
        from_ms: u64,
        until_ms: Option<u64>,
    },
    // The minutes the core wrote, never read from an app. Made nothing of unless they are still owed: a meeting heard
    // whole, kept in a file, its minutes writing.
    #[serde(skip)]
    Drafted {
        meeting: MeetingId,
        minutes: MinutesDraft,
    },
    // Minutes owed of a meeting never heard, left so by an app before the core's or by one without an engine: heard
    // from its start first, never written from no line at all. Made nothing of unless they are still owed.
    #[serde(skip)]
    Rehear {
        meeting: MeetingId,
    },
}

#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Edit {
    Rename {
        title: String,
    },
    SetDone {
        action: ActionId,
        done: bool,
    },
    // Left out, the owner is nobody and the date is none.
    SetOwner {
        action: ActionId,
        owner: Option<String>,
    },
    SetDue {
        action: ActionId,
        #[serde(default, with = "civil_date")]
        due: Option<Date>,
    },
    SetReminder {
        action: ActionId,
        on: bool,
    },
}

// The minutes as the core's writer hands them over: an action as heard, before it has an identifier or a state.
#[cfg_attr(test, derive(Debug, PartialEq))]
pub enum MinutesDraft {
    Writing,
    Written { summary: String, decisions: Vec<String>, actions: Vec<ActionDraft> },
    Failed,
}

#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct ActionDraft {
    pub text: String,
    pub owner: Option<String>,
    pub due: Option<Date>,
    pub source: Option<ActionSource>,
}

// When and where a change is made: two devices are reconciled from it later (E10).
#[derive(Clone, Copy)]
pub(crate) struct Stamp<'a> {
    pub at: UtcDateTime,
    pub device: &'a str,
}

impl Stamp<'_> {
    pub(crate) fn mark(self, meeting: &mut Meeting) {
        meeting.modified_at = self.at;
        self.device.clone_into(&mut meeting.device);
    }
}

impl Edit {
    // false when the meeting is left as it was: nothing is then dated, told or written.
    pub(crate) fn apply(self, meeting: &mut Meeting, stamp: Stamp) -> bool {
        let changed = match self {
            Edit::Rename { title } => {
                // A title typed with spaces around it keeps only its words; a blank one names nothing.
                let title = title.trim();
                let names = !title.is_empty() && meeting.title.as_deref() != Some(title);
                if names {
                    meeting.title = Some(title.to_owned());
                }
                names
            }
            Edit::SetDone { action, done } => correct(meeting, &action, |action| action.done = done),
            Edit::SetOwner { action, owner } => {
                let owner = owner.map(|name| name.trim().to_owned()).filter(|name| !name.is_empty());
                correct(meeting, &action, |action| action.owner = owner)
            }
            // A reminder has a day: it leaves with its date, and does not come back on its own with the next one.
            Edit::SetDue { action, due } => correct(meeting, &action, |action| {
                action.due = due;
                action.reminder &= due.is_some();
            }),
            Edit::SetReminder { action, on } => {
                correct(meeting, &action, |action| action.reminder = on && action.due.is_some())
            }
        };
        if changed {
            stamp.mark(meeting);
        }
        changed
    }
}

// Minutes not written yet have no action to correct.
fn correct(meeting: &mut Meeting, id: &ActionId, correction: impl FnOnce(&mut Action)) -> bool {
    let MinutesState::Written { minutes } = &mut meeting.minutes else { return false };
    let Some(action) = minutes.actions.iter_mut().find(|action| &action.id == id) else { return false };
    let before = action.clone();
    correction(action);
    *action != before
}

impl MinutesDraft {
    // What a whole transcript owes: minutes to write, or none at all when nothing was said, which no model is asked.
    pub(crate) fn owed(transcript: &[TranscriptLine]) -> Self {
        if transcript.iter().any(|line| line.said().is_some()) {
            Self::Writing
        } else {
            Self::Written { summary: String::new(), decisions: Vec::new(), actions: Vec::new() }
        }
    }

    // What the user gave while the meeting was transcribed stays: its title, and minutes already corrected.
    pub(crate) fn settle(self, meeting: &mut Meeting) {
        if !matches!(meeting.minutes, MinutesState::Written { .. }) {
            meeting.minutes = self.into();
        }
    }
}

impl From<MinutesDraft> for MinutesState {
    fn from(draft: MinutesDraft) -> Self {
        match draft {
            MinutesDraft::Writing => Self::Writing,
            MinutesDraft::Failed => Self::Failed,
            MinutesDraft::Written { summary, decisions, actions } => Self::Written {
                minutes: Minutes {
                    summary,
                    decisions,
                    actions: actions.into_iter().map(Action::from).collect(),
                    extra: Extra::new(),
                },
            },
        }
    }
}

impl From<ActionDraft> for Action {
    fn from(draft: ActionDraft) -> Self {
        Self {
            id: ActionId::random(),
            text: draft.text,
            owner: draft.owner,
            due: draft.due,
            done: false,
            reminder: false,
            source: draft.source,
            extra: Extra::new(),
        }
    }
}

impl Meeting {
    // Heard for the first time: nothing transcribed yet, and the core hears it from its start.
    pub(crate) fn begun(id: MeetingId, started_at: UtcDateTime, offset_minutes: Option<i16>, stamp: Stamp) -> Self {
        let mut meeting = Meeting {
            id,
            started_at,
            offset_minutes,
            duration_ms: 0,
            transcribed_ms: Some(0),
            modified_at: stamp.at,
            device: String::new(),
            title: None,
            speakers: Vec::new(),
            minutes: MinutesState::Writing,
            transcript: Vec::new(),
            extra: Extra::new(),
        };
        stamp.mark(&mut meeting);
        meeting
    }
}

#[cfg(test)]
mod tests {
    use time::macros::{date, utc_datetime};

    use super::*;
    use crate::meeting::testing::{heard, id, line, meeting, written};

    // One change of each kind, as the apps write them.
    const BY_THE_APPS: &str = include_str!("../../tests/fixtures/bridge/changes.json");

    const HERE: Stamp = Stamp { at: utc_datetime!(2026-11-11 8:00), device: "phone-2" };

    fn first() -> ActionId {
        ActionId::of_rank(0)
    }

    fn actions(meeting: &Meeting) -> &[Action] {
        let MinutesState::Written { minutes } = &meeting.minutes else { panic!("not written") };
        &minutes.actions
    }

    fn draft(texts: &[&str]) -> MinutesDraft {
        let action = |text: &&str| ActionDraft { text: (*text).into(), owner: None, due: None, source: None };
        MinutesDraft::Written {
            summary: String::new(),
            decisions: Vec::new(),
            actions: texts.iter().map(action).collect(),
        }
    }

    #[test]
    fn a_title_keeps_only_its_words_and_the_change_is_dated() {
        let mut renamed = meeting("recorded-1");
        assert!(Edit::Rename { title: "  Point produit  ".into() }.apply(&mut renamed, HERE));
        let expected = Meeting {
            title: Some("Point produit".into()),
            modified_at: HERE.at,
            device: "phone-2".into(),
            ..meeting("recorded-1")
        };
        assert_eq!(renamed, expected);
    }

    #[test]
    fn a_blank_or_unchanged_title_changes_nothing() {
        let titled = Meeting { title: Some("Point produit".into()), ..meeting("recorded-1") };
        for title in ["   ", "", "Point produit "] {
            let mut same = titled.clone();
            assert!(!Edit::Rename { title: title.into() }.apply(&mut same, HERE));
            assert_eq!(same, titled);
        }
    }

    #[test]
    fn an_action_is_corrected_one_value_at_a_time() {
        let mut corrected = meeting("recorded-1");
        let day = date!(2026 - 11 - 20);
        for edit in [
            Edit::SetDone { action: first(), done: true },
            Edit::SetOwner { action: first(), owner: Some(" Nathan ".into()) },
            Edit::SetDue { action: first(), due: Some(day) },
            Edit::SetReminder { action: first(), on: true },
        ] {
            assert!(edit.apply(&mut corrected, HERE));
        }
        let action = &actions(&corrected)[0];
        assert_eq!(
            (action.done, action.owner.as_deref(), action.due, action.reminder),
            (true, Some("Nathan"), Some(day), true)
        );
        assert_eq!((action.text.as_str(), corrected.modified_at), ("Préparer la démo", HERE.at));
    }

    #[test]
    fn a_blank_owner_is_nobody() {
        let mut owned = meeting("recorded-1");
        Edit::SetOwner { action: first(), owner: Some("Nathan".into()) }.apply(&mut owned, HERE);
        for nobody in [Some("  ".to_owned()), None] {
            let mut meeting = owned.clone();
            assert!(Edit::SetOwner { action: first(), owner: nobody }.apply(&mut meeting, HERE));
            assert_eq!(actions(&meeting)[0].owner, None);
        }
    }

    #[test]
    fn a_reminder_goes_with_its_date() {
        let mut meeting = meeting("recorded-1");
        for edit in [
            Edit::SetDue { action: first(), due: Some(date!(2026 - 11 - 20)) },
            Edit::SetReminder { action: first(), on: true },
            Edit::SetDue { action: first(), due: None },
            Edit::SetDue { action: first(), due: Some(date!(2026 - 11 - 27)) },
        ] {
            assert!(edit.apply(&mut meeting, HERE));
        }
        assert!(!actions(&meeting)[0].reminder);
    }

    #[test]
    fn a_reminder_without_a_date_is_not_set() {
        let mut undated = meeting("recorded-1");
        assert!(!Edit::SetReminder { action: first(), on: true }.apply(&mut undated, HERE));
        assert_eq!(undated, meeting("recorded-1"));
    }

    #[test]
    fn a_correction_that_changes_nothing_is_not_dated() {
        let mut same = meeting("recorded-1");
        for edit in [
            Edit::SetDone { action: first(), done: false },
            Edit::SetOwner { action: first(), owner: None },
            Edit::SetDue { action: first(), due: None },
            Edit::SetReminder { action: first(), on: false },
        ] {
            assert!(!edit.apply(&mut same, HERE));
        }
        assert_eq!(same, meeting("recorded-1"));
    }

    #[test]
    fn an_unknown_action_or_minutes_not_written_change_nothing() {
        let mut written_minutes = meeting("recorded-1");
        assert!(!Edit::SetDone { action: ActionId::of_rank(7), done: true }.apply(&mut written_minutes, HERE));
        let mut writing = Meeting { minutes: MinutesState::Writing, ..meeting("recorded-1") };
        assert!(!Edit::SetDone { action: first(), done: true }.apply(&mut writing, HERE));
    }

    #[test]
    fn minutes_not_written_yet_are_settled_by_the_writer() {
        let mut writing = Meeting { minutes: MinutesState::Writing, ..meeting("recorded-1") };
        draft(&["Préparer la démo"]).settle(&mut writing);
        assert_eq!(actions(&writing)[0].text, "Préparer la démo");
    }

    #[test]
    fn minutes_already_written_are_not_taken_back() {
        let mut corrected = meeting("recorded-1");
        Edit::SetDone { action: first(), done: true }.apply(&mut corrected, HERE);
        for late in [draft(&["Préparer la démo"]), MinutesDraft::Failed, MinutesDraft::Writing] {
            let mut kept = corrected.clone();
            late.settle(&mut kept);
            assert_eq!(kept.minutes, corrected.minutes);
        }
    }

    #[test]
    fn each_action_of_new_minutes_gets_its_own_identifier() {
        let MinutesState::Written { minutes } =
            MinutesState::from(draft(&["Préparer la démo", "Envoyer le compte rendu"]))
        else {
            panic!("not written")
        };
        let [first, second] = minutes.actions.as_slice() else { panic!("not two actions") };
        assert_ne!(first.id, second.id);
        assert!(!first.done && !first.reminder);
    }

    #[test]
    fn a_whole_transcript_owes_minutes_unless_nothing_was_said() {
        assert_eq!(MinutesDraft::owed(&[line(0, "Bonjour à tous.")]), MinutesDraft::Writing);
        let silent = TranscriptLine { at_ms: 0, text: None, speaker: None, extra: Extra::new() };
        let empty = draft(&[]);
        assert_eq!(MinutesDraft::owed(&[]), empty);
        assert_eq!(MinutesDraft::owed(&[silent, line(30_000, "  ")]), empty);
        assert_eq!(written(&[]), MinutesState::from(empty));
    }

    #[test]
    fn the_changes_the_apps_write_are_read() {
        let edit = |edit| Change::Edit { meeting: id("recorded-1"), edit };
        let expected = vec![
            edit(Edit::Rename { title: "Point produit".into() }),
            edit(Edit::SetDone { action: first(), done: true }),
            edit(Edit::SetOwner { action: first(), owner: Some("Nathan".into()) }),
            edit(Edit::SetOwner { action: first(), owner: None }),
            edit(Edit::SetDue { action: first(), due: Some(date!(2026 - 11 - 20)) }),
            edit(Edit::SetDue { action: first(), due: None }),
            edit(Edit::SetReminder { action: first(), on: true }),
            Change::Delete { meetings: vec![id("recorded-1"), id("recorded-3")] },
            Change::Rewrite { meeting: id("recorded-1") },
            Change::Lend { meetings: vec![heard("sample-1")] },
        ];
        assert_eq!(serde_json::from_str::<Vec<Change>>(BY_THE_APPS).unwrap(), expected);
    }

    #[test]
    fn what_is_not_a_change_is_refused() {
        let edit = |edit: &str| format!("{{\"type\":\"edit\",\"meeting\":\"recorded-1\",\"edit\":{edit}}}");
        for text in [
            String::new(),
            "[]".into(),
            "{}".into(),
            "{\"type\":\"forget\"}".into(),
            "{\"type\":\"delete\"}".into(),
            // The Kotlin recorder's, gone with it.
            "{\"type\":\"record\",\"id\":\"recorded-9\",\"startedAt\":\"2026-11-11T07:00:00Z\",\"durationMs\":60000,\"minutes\":{\"type\":\"writing\"}}"
                .into(),
            // The recorder's own: an app that sends them is refused.
            "{\"type\":\"begin\",\"id\":\"recorded-1\",\"startedAt\":\"2026-11-11T07:00:00Z\",\"offsetMinutes\":60}"
                .into(),
            "{\"type\":\"lasted\",\"meeting\":\"recorded-1\",\"durationMs\":60000}".into(),
            "{\"type\":\"lasted\",\"meeting\":\"recorded-1\",\"duration_ms\":60000}".into(),
            // The Kotlin transcription's, gone with it: the core hears every meeting.
            "{\"type\":\"transcribed\",\"meeting\":\"recorded-1\",\"transcript\":[],\"minutes\":{\"type\":\"writing\"}}"
                .into(),
            "{\"type\":\"rehear\",\"meeting\":\"recorded-1\"}".into(),
            "{\"type\":\"heard\",\"meeting\":\"recorded-1\",\"lines\":[],\"fromMs\":0,\"untilMs\":null}".into(),
            "{\"type\":\"drafted\",\"meeting\":\"recorded-1\",\"minutes\":{\"type\":\"failed\"}}".into(),
            "{\"type\":\"rewrite\"}".into(),
            // An identifier names a file: one that would leave the folder never reaches the meetings.
            "{\"type\":\"delete\",\"meetings\":[\"../recorded-1\"]}".into(),
            edit("{\"type\":\"rename\"}"),
            edit("{\"type\":\"setDone\",\"action\":\"0/1\",\"done\":true}"),
            edit("{\"type\":\"setDue\",\"action\":\"0\",\"due\":\"demain\"}"),
        ] {
            assert!(serde_json::from_str::<Change>(&text).is_err(), "{text}");
        }
    }
}
