use serde::Deserialize;
use time::{Date, UtcDateTime};

use super::{
    Action, ActionId, ActionSource, Extra, Meeting, MeetingId, Minutes, MinutesState, TranscriptLine, civil_date,
    instant,
};

// What an app or the recorder asks of the meetings. A value, never a function: it crosses the bridge as text, and
// is read here as it stands. tests/fixtures/bridge/changes.json holds one of each kind, which every app writes in
// its own tests: a change of shape here is a change of the bridge.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Change {
    Edit { meeting: MeetingId, edit: Edit },
    // For good, its file too.
    Delete { meetings: Vec<MeetingId> },
    Record(Snapshot),
    // Samples: changed like the others for as long as the app runs, never written.
    Lend { meetings: Vec<Meeting> },
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

// Where the recorder stands: sent at each new line, every few seconds of audio, and once the minutes are settled.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub id: MeetingId,
    #[serde(with = "instant")]
    pub started_at: UtcDateTime,
    pub duration_ms: u64,
    #[serde(default)]
    pub transcript: Vec<TranscriptLine>,
    pub minutes: MinutesDraft,
}

// The minutes as their writer hands them over: an action as heard, before it has an identifier or a state.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum MinutesDraft {
    Writing,
    Written { summary: String, decisions: Vec<String>, actions: Vec<ActionDraft> },
    Failed,
    Unavailable,
}

#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct ActionDraft {
    pub text: String,
    pub owner: Option<String>,
    #[serde(default, with = "civil_date")]
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
    fn mark(self, meeting: &mut Meeting) {
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

impl Snapshot {
    // The meeting to keep and, apart, its lines. `known` is the meeting as kept so far, None the first time.
    pub(crate) fn merge(self, known: Option<Meeting>, stamp: Stamp) -> (Meeting, Vec<TranscriptLine>) {
        let mut meeting = known.unwrap_or_else(|| Meeting {
            id: self.id,
            started_at: self.started_at,
            duration_ms: 0,
            modified_at: stamp.at,
            device: String::new(),
            title: None,
            speakers: Vec::new(),
            minutes: MinutesState::Writing,
            transcript: Vec::new(),
            extra: Extra::new(),
        });
        meeting.duration_ms = self.duration_ms;
        // What the user gave while the meeting was transcribed stays: its title, and minutes already corrected.
        if !matches!(meeting.minutes, MinutesState::Written { .. }) {
            meeting.minutes = self.minutes.into();
        }
        stamp.mark(&mut meeting);
        (meeting, self.transcript)
    }
}

impl From<MinutesDraft> for MinutesState {
    fn from(draft: MinutesDraft) -> Self {
        match draft {
            MinutesDraft::Writing => Self::Writing,
            MinutesDraft::Failed => Self::Failed,
            MinutesDraft::Unavailable => Self::Unavailable,
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
    // Minutes left writing belong to an app that was killed: without the audio, nothing can resume them.
    pub(crate) fn interrupted(mut self) -> Self {
        if self.minutes == MinutesState::Writing {
            self.minutes = MinutesState::Failed;
        }
        self
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

    fn snapshot(minutes: MinutesDraft) -> Snapshot {
        Snapshot {
            id: id("recorded-1"),
            started_at: utc_datetime!(2026-11-10 9:30),
            duration_ms: 90_000,
            transcript: vec![line(0, "Bonjour à tous.")],
            minutes,
        }
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
    fn a_first_snapshot_makes_the_meeting() {
        let (recorded, lines) = snapshot(MinutesDraft::Writing).merge(None, HERE);
        let expected = Meeting {
            duration_ms: 90_000,
            modified_at: HERE.at,
            device: "phone-2".into(),
            minutes: MinutesState::Writing,
            ..meeting("recorded-1")
        };
        assert_eq!(recorded, expected);
        assert_eq!(lines, [line(0, "Bonjour à tous.")]);
    }

    #[test]
    fn a_snapshot_leaves_the_title_given_while_the_meeting_was_transcribed() {
        let (mut recorded, _) = snapshot(MinutesDraft::Writing).merge(None, HERE);
        Edit::Rename { title: "Point produit".into() }.apply(&mut recorded, HERE);
        let (settled, _) = snapshot(draft(&["Préparer la démo"])).merge(Some(recorded), HERE);
        assert_eq!(settled.title.as_deref(), Some("Point produit"));
        assert_eq!(actions(&settled)[0].text, "Préparer la démo");
    }

    #[test]
    fn each_action_of_new_minutes_gets_its_own_identifier() {
        let (settled, _) = snapshot(draft(&["Préparer la démo", "Envoyer le compte rendu"])).merge(None, HERE);
        let [first, second] = actions(&settled) else { panic!("not two actions") };
        assert_ne!(first.id, second.id);
        assert!(!first.done && !first.reminder);
    }

    #[test]
    fn a_snapshot_does_not_take_back_minutes_already_written() {
        let (mut settled, _) = snapshot(draft(&["Préparer la démo"])).merge(None, HERE);
        let action = actions(&settled)[0].id.clone();
        Edit::SetDone { action, done: true }.apply(&mut settled, HERE);
        for late in [draft(&["Préparer la démo"]), MinutesDraft::Failed] {
            let (kept, _) = snapshot(late).merge(Some(settled.clone()), HERE);
            assert_eq!(kept.minutes, settled.minutes);
        }
    }

    #[test]
    fn minutes_left_writing_by_a_killed_app_have_failed() {
        let writing = Meeting { minutes: MinutesState::Writing, ..meeting("recorded-1") };
        assert_eq!(writing.interrupted().minutes, MinutesState::Failed);
        assert_eq!(meeting("recorded-1").interrupted(), meeting("recorded-1"));
        assert_eq!(written(&[]), MinutesState::from(draft(&[])));
    }

    #[test]
    fn the_changes_the_apps_write_are_read() {
        let edit = |edit| Change::Edit { meeting: id("recorded-1"), edit };
        let recorded = |duration_ms, transcript, minutes| {
            Change::Record(Snapshot {
                id: id("recorded-9"),
                started_at: utc_datetime!(2026-11-11 7:00),
                duration_ms,
                transcript,
                minutes,
            })
        };
        let heard_and_written = MinutesDraft::Written {
            summary: "Point sur la démo.".into(),
            decisions: vec!["La démo a lieu le 15 novembre.".into()],
            actions: vec![
                ActionDraft {
                    text: "Préparer la démo".into(),
                    owner: Some("Lucas".into()),
                    due: Some(date!(2026 - 11 - 14)),
                    source: Some(ActionSource {
                        quote: "Je prépare la démo.".into(),
                        speaker: None,
                        at_ms: 724_000,
                        extra: Extra::new(),
                    }),
                },
                ActionDraft { text: "Rédiger le registre RGPD".into(), owner: None, due: None, source: None },
            ],
        };
        let silence = TranscriptLine { at_ms: 30_000, text: None, speaker: None, extra: Extra::new() };
        let expected = vec![
            edit(Edit::Rename { title: "Point produit".into() }),
            edit(Edit::SetDone { action: first(), done: true }),
            edit(Edit::SetOwner { action: first(), owner: Some("Nathan".into()) }),
            edit(Edit::SetOwner { action: first(), owner: None }),
            edit(Edit::SetDue { action: first(), due: Some(date!(2026 - 11 - 20)) }),
            edit(Edit::SetDue { action: first(), due: None }),
            edit(Edit::SetReminder { action: first(), on: true }),
            Change::Delete { meetings: vec![id("recorded-1"), id("recorded-3")] },
            recorded(60_000, Vec::new(), MinutesDraft::Writing),
            recorded(90_000, vec![line(0, "Bonjour à tous."), silence], heard_and_written),
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
