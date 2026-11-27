// What a day makes of the actions of every meeting: the board of the Actions tab, by due date and by person, and what
// is late in each meeting. Worked out here from the day the app gives, so that no app works it out again.
// docs/adr/0017-tableau-des-actions.md.

mod person;

// For the tests of the example the apps share, and for them alone.
#[cfg(test)]
pub(crate) use person::key as person_key;

use std::cmp::Reverse;
use std::collections::{BTreeMap, HashMap, HashSet};

use serde::{Deserialize, Serialize};
use time::{Date, UtcDateTime};

use crate::meeting::{Action, ActionId, Meeting, MeetingId, MinutesState, civil_date};

// An action is known by its meeting as well: two meetings may each hold an action `0`.
#[derive(Deserialize, PartialEq, Eq)]
#[cfg_attr(test, derive(Debug))]
pub struct ActionKey {
    pub meeting: MeetingId,
    pub action: ActionId,
}

// What an app asks of the board. `lingering` are the actions the user has just ticked: they keep their place for a
// moment, so no card moves under the finger, and count as done all the same.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct Ask {
    // The day of the app, in its own time zone, and never a default: the core reads no clock (clippy.toml).
    #[serde(with = "civil_date::required")]
    pub today: Date,
    #[serde(default)]
    pub lingering: Vec<ActionKey>,
}

// Where a due date stands, a calendar day at a time: an action due yesterday is late from midnight.
#[derive(Clone, Copy, PartialEq, Eq, Serialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(rename_all = "camelCase")]
pub enum DueGroup {
    Late,
    Today,
    Tomorrow,
    // Within the week.
    NextDays,
    Later,
}

impl DueGroup {
    fn of(due: Date, today: Date) -> Self {
        match (due - today).whole_days() {
            ..0 => Self::Late,
            0 => Self::Today,
            1 => Self::Tomorrow,
            2..7 => Self::NextDays,
            7.. => Self::Later,
        }
    }
}

// An action by reference, with what the day decides of it: the text, the owner and the tick stay those of the meeting,
// told by its own events.
#[derive(Serialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(rename_all = "camelCase")]
pub struct Placed {
    meeting: MeetingId,
    action: ActionId,
    // None without a due date.
    #[serde(skip_serializing_if = "Option::is_none")]
    group: Option<DueGroup>,
    // Only for an action that is late and not done: a ticked one that lingers is no longer late by anything.
    #[serde(skip_serializing_if = "Option::is_none")]
    days_late: Option<u32>,
}

impl Placed {
    fn of(tracked: &Tracked, today: Date) -> Self {
        let due = tracked.action.due;
        let late = tracked.is_late(today) && !tracked.action.done;
        let days_late = due.filter(|_| late).and_then(|due| u32::try_from((today - due).whole_days()).ok());
        Self {
            meeting: tracked.meeting.id.clone(),
            action: tracked.action.id.clone(),
            group: due.map(|due| DueGroup::of(due, today)),
            days_late,
        }
    }
}

#[derive(Serialize)]
#[cfg_attr(test, derive(Debug))]
pub struct Grouped {
    group: DueGroup,
    actions: Vec<Placed>,
}

// The actions of one owner across every meeting. No key and no name for the actions nobody owns.
#[derive(Serialize)]
#[cfg_attr(test, derive(Debug))]
pub struct Person {
    #[serde(skip_serializing_if = "Option::is_none")]
    key: Option<String>,
    // The spelling of the latest meeting: the last the user wrote or kept.
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    // What is still to do, and what was just ticked.
    open: Vec<Placed>,
    late: usize,
    done: usize,
    total: usize,
}

// The two ways to read the same actions, and what is late in each meeting. Crosses the bridge as text, written here as
// it stands: tests/fixtures/bridge/board.json holds an example, which every app reads in its own tests.
#[derive(Serialize)]
#[cfg_attr(test, derive(Debug))]
#[serde(rename_all = "camelCase")]
pub struct Board {
    // The day it was made for: an app that moved on to the next one sets this one aside.
    #[serde(with = "civil_date::required")]
    today: Date,
    // Lacking an owner or a due date: they cannot be followed yet.
    to_complete: Vec<Placed>,
    // By due date, the most urgent group first, and no group that is empty.
    groups: Vec<Grouped>,
    done: Vec<Placed>,
    // What the groups and the actions to complete hold, and what the late group holds.
    open: usize,
    late: usize,
    // The people with the most late, then the most open, first; the actions nobody owns last.
    people: Vec<Person>,
    // Only the meetings with an action late and not done, ticked ones that linger left out.
    late_by_meeting: BTreeMap<MeetingId, usize>,
}

// An action with the meeting that agreed to it, and its place among that meeting's actions.
struct Tracked<'a> {
    meeting: &'a Meeting,
    rank: usize,
    action: &'a Action,
}

impl<'a> Tracked<'a> {
    // Minutes not written yet hold no action. An identifier held twice in a meeting, as a damaged file may, is told
    // once: the first, which is the one an edit reaches, so that a reference never names two actions.
    fn all(meetings: &[&'a Meeting]) -> Vec<Self> {
        let mut all = Vec::new();
        let mut held = HashSet::new();
        for &meeting in meetings {
            if let MinutesState::Written { minutes } = &meeting.minutes {
                held.clear();
                let first = minutes.actions.iter().enumerate().filter(|(_, action)| held.insert(&action.id));
                all.extend(first.map(|(rank, action)| Tracked { meeting, rank, action }));
            }
        }
        all
    }

    // Past its due date, done or not: what the late group holds, and what a person counts late among what is shown.
    fn is_late(&self, today: Date) -> bool {
        self.action.due.is_some_and(|due| DueGroup::of(due, today) == DueGroup::Late)
    }

    // Nobody, for a blank name: the user cannot write one, a model or an old file may.
    fn owner(&self) -> Option<&'a str> {
        self.action.owner.as_deref().filter(|name| !name.trim().is_empty())
    }

    // Where two actions tie on all else: the earlier meeting, then the order of the list (the identifier), then the
    // order of its minutes. A total order, so that the board does not depend on the order the meetings are given in.
    fn place(&self) -> (UtcDateTime, &'a MeetingId, usize) {
        (self.meeting.started_at, &self.meeting.id, self.rank)
    }

    // The latest meeting first, then as `place`.
    fn recent(&self) -> (Reverse<UtcDateTime>, &'a MeetingId, usize) {
        (Reverse(self.meeting.started_at), &self.meeting.id, self.rank)
    }
}

// The actions just ticked, looked up by borrowed identifiers: no key is built for each action.
struct Lingering<'a>(HashSet<(&'a MeetingId, &'a ActionId)>);

impl<'a> Lingering<'a> {
    fn new(keys: &'a [ActionKey]) -> Self {
        Self(keys.iter().map(|key| (&key.meeting, &key.action)).collect())
    }

    // What a list still shows: what is not done, and what was just ticked.
    fn shows(&self, tracked: &Tracked) -> bool {
        !tracked.action.done || self.0.contains(&(&tracked.meeting.id, &tracked.action.id))
    }
}

impl Board {
    // `meetings` in any order, samples included.
    pub(crate) fn of(meetings: &[&Meeting], ask: &Ask) -> Self {
        let today = ask.today;
        let lingering = Lingering::new(&ask.lingering);
        let tracked = Tracked::all(meetings);

        let (shown, mut done): (Vec<&Tracked>, Vec<&Tracked>) =
            tracked.iter().partition(|tracked| lingering.shows(tracked));
        done.sort_by_key(|tracked| tracked.recent());

        let (mut to_complete, mut dated) = (Vec::new(), Vec::new());
        for tracked in &shown {
            match (tracked.owner(), tracked.action.due) {
                (Some(_), Some(due)) => dated.push((due, *tracked)),
                _ => to_complete.push(*tracked),
            }
        }
        // The earliest meeting first: it has waited longest.
        to_complete.sort_by_key(|tracked| tracked.place());
        dated.sort_by_key(|(due, tracked)| (*due, tracked.place()));

        let mut groups: Vec<Grouped> = Vec::new();
        for (due, tracked) in dated {
            let group = DueGroup::of(due, today);
            match groups.last_mut() {
                Some(last) if last.group == group => last.actions.push(Placed::of(tracked, today)),
                _ => groups.push(Grouped { group, actions: vec![Placed::of(tracked, today)] }),
            }
        }
        let late =
            groups.iter().find(|grouped| grouped.group == DueGroup::Late).map_or(0, |grouped| grouped.actions.len());

        let mut late_by_meeting = BTreeMap::new();
        for item in tracked.iter().filter(|item| !item.action.done && item.is_late(today)) {
            *late_by_meeting.entry(item.meeting.id.clone()).or_insert(0) += 1;
        }

        Self {
            today,
            to_complete: to_complete.into_iter().map(|tracked| Placed::of(tracked, today)).collect(),
            groups,
            done: done.into_iter().map(|tracked| Placed::of(tracked, today)).collect(),
            open: shown.len(),
            late,
            people: people_of(&tracked, &lingering, today),
            late_by_meeting,
        }
    }
}

fn people_of(tracked: &[Tracked], lingering: &Lingering, today: Date) -> Vec<Person> {
    // Told apart by the first name as written, never by the voice (product rule 4).
    let mut owned: HashMap<String, Vec<&Tracked>> = HashMap::new();
    let mut nobody = Vec::new();
    for tracked in tracked {
        match tracked.owner() {
            Some(name) => owned.entry(person::key(name)).or_default().push(tracked),
            None => nobody.push(tracked),
        }
    }
    let mut people: Vec<Person> =
        owned.into_iter().map(|(key, actions)| person_of(Some(key), &actions, lingering, today)).collect();
    // The keys are all different: nothing is left to a tie.
    people.sort_by(|a, b| {
        (Reverse(a.late), Reverse(a.open.len()), &a.key).cmp(&(Reverse(b.late), Reverse(b.open.len()), &b.key))
    });
    if !nobody.is_empty() {
        people.push(person_of(None, &nobody, lingering, today));
    }
    people
}

fn person_of(key: Option<String>, actions: &[&Tracked], lingering: &Lingering, today: Date) -> Person {
    // The first of the latest meeting wins a tie on its start, as the first of the list does: `max_by_key` would take
    // the last.
    let latest = actions.iter().min_by_key(|tracked| tracked.recent());
    let mut open: Vec<&Tracked> = actions.iter().copied().filter(|tracked| lingering.shows(tracked)).collect();
    // Without a due date last.
    open.sort_by_key(|tracked| (tracked.action.due.is_none(), tracked.action.due, tracked.place()));
    Person {
        // Whoever owns nothing has no name, though a blank one may be written: only an owner is named.
        name: key.as_ref().and(latest).and_then(|tracked| tracked.action.owner.clone()),
        key,
        late: open.iter().filter(|tracked| tracked.is_late(today)).count(),
        open: open.into_iter().map(|tracked| Placed::of(tracked, today)).collect(),
        done: actions.iter().filter(|tracked| tracked.action.done).count(),
        total: actions.len(),
    }
}

#[cfg(test)]
mod tests {
    use time::macros::date;
    use time::{Duration, Month, Time};

    use super::*;
    use crate::meeting::testing::meeting;
    use crate::meeting::{Extra, Minutes};

    const TODAY: Date = date!(2026 - 10 - 26);

    fn in_days(days: i64) -> Date {
        TODAY + Duration::days(days)
    }

    // October 2026, at the hour.
    fn at(day: u8, hour: u8) -> UtcDateTime {
        UtcDateTime::new(
            Date::from_calendar_date(2026, Month::October, day).unwrap(),
            Time::from_hms(hour, 0, 0).unwrap(),
        )
    }

    // One action in a meeting of its own: as the cases of the Kotlin this replaces were written.
    #[derive(Clone)]
    struct Spec {
        text: &'static str,
        owner: Option<&'static str>,
        due: Option<Date>,
        done: bool,
        meeting_at: UtcDateTime,
    }

    fn on(text: &'static str, due: Date) -> Spec {
        Spec { due: Some(due), ..undated(text) }
    }

    fn undated(text: &'static str) -> Spec {
        Spec { text, owner: Some("Nathan"), due: None, done: false, meeting_at: at(20, 10) }
    }

    impl Spec {
        fn done(self) -> Self {
            Self { done: true, ..self }
        }

        fn owner(self, owner: Option<&'static str>) -> Self {
            Self { owner, ..self }
        }

        fn meeting(self, meeting_at: UtcDateTime) -> Self {
            Self { meeting_at, ..self }
        }
    }

    // The meetings of some specs, `m00`, `m01`, … each with its action `0`, and what the board makes of them.
    struct Scene(Vec<Spec>);

    impl Scene {
        fn meetings(&self) -> Vec<Meeting> {
            let one = |(index, spec): (usize, &Spec)| {
                let action = Action {
                    id: ActionId::of_rank(0),
                    text: spec.text.into(),
                    owner: spec.owner.map(Into::into),
                    due: spec.due,
                    done: spec.done,
                    reminder: false,
                    source: None,
                    extra: Extra::new(),
                };
                let minutes = Minutes {
                    summary: String::new(),
                    decisions: Vec::new(),
                    actions: vec![action],
                    extra: Extra::new(),
                };
                let id = MeetingId::new(format!("m{index:02}")).unwrap();
                Meeting {
                    id,
                    started_at: spec.meeting_at,
                    minutes: MinutesState::Written { minutes },
                    ..meeting("m99")
                }
            };
            self.0.iter().enumerate().map(one).collect()
        }

        fn board(&self, today: Date, lingering: &[usize]) -> Board {
            let meetings = self.meetings();
            let refs: Vec<&Meeting> = meetings.iter().collect();
            let lingering = lingering.iter().map(|index| key(*index)).collect();
            Board::of(&refs, &Ask { today, lingering })
        }

        fn today(&self) -> Board {
            self.board(TODAY, &[])
        }

        // The texts of what a list holds, in its order.
        fn said(&self, placed: &[Placed]) -> Vec<&'static str> {
            placed.iter().map(|placed| self.0[placed.meeting.as_str()[1..].parse::<usize>().unwrap()].text).collect()
        }
    }

    fn key(index: usize) -> ActionKey {
        ActionKey { meeting: MeetingId::new(format!("m{index:02}")).unwrap(), action: ActionId::of_rank(0) }
    }

    fn group(board: &Board, which: DueGroup) -> Option<&Grouped> {
        board.groups.iter().find(|grouped| grouped.group == which)
    }

    fn names(board: &Board) -> Vec<Option<&str>> {
        board.people.iter().map(|person| person.name.as_deref()).collect()
    }

    #[test]
    fn due_dates_fall_in_calendar_groups() {
        let groups: Vec<DueGroup> = [-3, -1, 0, 1, 2, 6, 7, 40].map(|days| DueGroup::of(in_days(days), TODAY)).into();
        let (late, today, tomorrow, next, later) =
            (DueGroup::Late, DueGroup::Today, DueGroup::Tomorrow, DueGroup::NextDays, DueGroup::Later);
        assert_eq!(groups, [late, late, today, tomorrow, next, next, later, later]);
    }

    #[test]
    fn late_is_counted_in_days() {
        let scene = Scene(vec![on("hier", in_days(-1)), on("il y a neuf jours", in_days(-9)).meeting(at(21, 10))]);
        let board = scene.today();
        let late = &group(&board, DueGroup::Late).unwrap().actions;
        assert_eq!(late.iter().map(|placed| placed.days_late).collect::<Vec<_>>(), [Some(9), Some(1)]);
    }

    #[test]
    fn what_is_not_late_or_not_dated_is_late_by_nothing() {
        let scene = Scene(vec![on("demain", in_days(1)), undated("sans date"), on("faite", in_days(-3)).done()]);
        let board = scene.today();
        let placed: Vec<_> = board
            .groups
            .iter()
            .flat_map(|grouped| &grouped.actions)
            .chain(&board.to_complete)
            .chain(&board.done)
            .collect();
        assert_eq!(placed.len(), 3);
        assert!(placed.iter().all(|placed| placed.days_late.is_none()));
        // A late action that is done still knows its group, which is what tells it was late.
        assert_eq!(board.done[0].group, Some(DueGroup::Late));
    }

    #[test]
    fn groups_come_most_urgent_first_and_the_done_ones_apart() {
        let scene = Scene(vec![
            on("plus tard", in_days(30)),
            on("faite", in_days(-4)).done(),
            on("demain", in_days(1)),
            on("en retard", in_days(-2)),
            on("aujourd’hui", TODAY),
            on("cette semaine", in_days(3)),
        ]);
        let board = scene.today();
        let order: Vec<DueGroup> = board.groups.iter().map(|grouped| grouped.group).collect();
        let (late, today, tomorrow, next, later) =
            (DueGroup::Late, DueGroup::Today, DueGroup::Tomorrow, DueGroup::NextDays, DueGroup::Later);
        assert_eq!(order, [late, today, tomorrow, next, later]);
        assert_eq!(scene.said(&board.done), ["faite"]);
        assert_eq!((board.open, board.late), (5, 1));
    }

    #[test]
    fn a_group_with_nothing_in_it_is_not_there() {
        let scene = Scene(vec![on("demain", in_days(1)), on("dans un mois", in_days(30))]);
        let order: Vec<DueGroup> = scene.today().groups.iter().map(|grouped| grouped.group).collect();
        assert_eq!(order, [DueGroup::Tomorrow, DueGroup::Later]);
    }

    #[test]
    fn in_a_group_the_soonest_due_comes_first_then_the_earliest_meeting() {
        let scene = Scene(vec![
            on("jeudi, réunion de mardi", in_days(3)).meeting(at(20, 14)),
            on("mercredi", in_days(2)),
            on("jeudi, réunion de lundi", in_days(3)).meeting(at(19, 9)),
        ]);
        let board = scene.today();
        assert_eq!(
            scene.said(&board.groups[0].actions),
            ["mercredi", "jeudi, réunion de lundi", "jeudi, réunion de mardi"]
        );
        assert_eq!(board.groups.len(), 1);
    }

    #[test]
    fn the_done_ones_show_the_latest_meeting_first() {
        let scene =
            Scene(vec![undated("ancienne").done().meeting(at(2, 10)), undated("récente").done().meeting(at(23, 10))]);
        let board = scene.today();
        assert_eq!(scene.said(&board.done), ["récente", "ancienne"]);
        assert_eq!(board.open, 0);
    }

    #[test]
    fn a_just_ticked_action_keeps_its_place_and_its_count() {
        let scene = Scene(vec![on("Corriger le contraste des badges", in_days(-2)).done()]);
        let board = scene.board(TODAY, &[0]);
        assert_eq!(board.groups.len(), 1);
        assert_eq!(scene.said(&board.groups[0].actions), ["Corriger le contraste des badges"]);
        assert!(board.done.is_empty());
        assert_eq!(board.late, 1);
        // Late and ticked: no days to say.
        assert_eq!(board.groups[0].actions[0].days_late, None);
    }

    #[test]
    fn an_action_without_owner_or_due_date_is_to_complete_and_counts_as_open() {
        let scene = Scene(vec![
            on("sans responsable", in_days(-2)).owner(None).meeting(at(23, 10)),
            undated("sans date").meeting(at(20, 10)),
            on("complète", in_days(1)),
            undated("faite sans date").done(),
        ]);
        let board = scene.today();
        assert_eq!(scene.said(&board.to_complete), ["sans date", "sans responsable"]);
        let order: Vec<DueGroup> = board.groups.iter().map(|grouped| grouped.group).collect();
        assert_eq!(order, [DueGroup::Tomorrow]);
        assert_eq!(scene.said(&board.done), ["faite sans date"]);
        assert_eq!(board.open, 3);
        // Late, but not in the late group: the line above the list counts what the list shows.
        assert_eq!(board.late, 0);
    }

    #[test]
    fn a_completed_action_joins_its_group() {
        let with_owner = undated("Réserver la salle").owner(Some("Adam"));
        assert_eq!(Scene(vec![with_owner.clone()]).today().to_complete.len(), 1);
        let board = Scene(vec![Spec { due: Some(in_days(1)), ..with_owner }]).today();
        assert!(board.to_complete.is_empty());
        assert_eq!(board.groups.iter().map(|grouped| grouped.group).collect::<Vec<_>>(), [DueGroup::Tomorrow]);
    }

    #[test]
    fn a_day_later_everything_is_a_day_closer() {
        let scene = Scene(vec![on("demain", in_days(1)), on("aujourd’hui", TODAY), on("hier", in_days(-1))]);
        let groups = |board: &Board| -> Vec<(DueGroup, usize)> {
            board.groups.iter().map(|grouped| (grouped.group, grouped.actions.len())).collect()
        };
        assert_eq!(groups(&scene.today()), [(DueGroup::Late, 1), (DueGroup::Today, 1), (DueGroup::Tomorrow, 1)]);
        let next = scene.board(in_days(1), &[]);
        assert_eq!(groups(&next), [(DueGroup::Late, 2), (DueGroup::Today, 1)]);
        assert_eq!(
            next.groups[0].actions.iter().map(|placed| placed.days_late).collect::<Vec<_>>(),
            [Some(2), Some(1)]
        );
        assert_eq!(next.today, in_days(1));
    }

    #[test]
    fn a_person_is_shown_with_the_spelling_of_the_latest_meeting() {
        let scene = Scene(vec![
            undated("a").owner(Some("clement")).meeting(at(19, 10)),
            undated("b").owner(Some("Clément")).meeting(at(23, 10)),
            undated("c").owner(Some("CLEMENT")).meeting(at(21, 10)),
        ]);
        let board = scene.today();
        assert_eq!(board.people.len(), 1);
        assert_eq!(board.people[0].name.as_deref(), Some("Clément"));
        assert_eq!(board.people[0].key.as_deref(), Some("clement"));
        assert_eq!(board.people[0].total, 3);
    }

    // Two meetings at the same second, as two recorded in a row on one phone: the first of the list wins the tie, as it
    // did, and never the last, which is what `max_by_key` would give.
    #[test]
    fn a_tie_on_the_start_gives_the_spelling_of_the_first_meeting_of_the_list() {
        let scene = Scene(vec![
            undated("a").owner(Some("Zoe")).meeting(at(23, 10)),
            undated("b").owner(Some("Zoé")).meeting(at(23, 10)),
        ]);
        assert_eq!(names(&scene.today()), [Some("Zoe")]);
        let reversed = Scene(scene.0.iter().rev().cloned().collect());
        // m00 is now the one that says Zoé: the identifier orders the tie, not the position in the list given.
        assert_eq!(names(&reversed.today()), [Some("Zoé")]);
    }

    // Product rule 4: the voice never groups anyone.
    #[test]
    fn people_are_grouped_by_name_never_by_speaker() {
        let scene = Scene(vec![
            on("a", in_days(3)).owner(Some("Nathan")),
            on("b", in_days(3)).owner(Some("Adam")),
            on("c", in_days(3)).owner(Some("Nathan")),
        ]);
        let board = scene.today();
        assert_eq!(names(&board), [Some("Nathan"), Some("Adam")]);
        assert_eq!(board.people.iter().map(|person| person.total).collect::<Vec<_>>(), [2, 1]);
    }

    #[test]
    fn the_most_late_come_first_then_the_busiest_then_by_name() {
        let owned = |text, owner, due| on(text, due).owner(Some(owner));
        let scene = Scene(vec![
            owned("1", "Zoé", in_days(-1)),
            owned("2", "Adam", in_days(3)),
            owned("3", "Adam", in_days(3)),
            owned("4", "Lucas", in_days(3)),
            owned("5", "Bruno", in_days(3)),
            owned("6", "Nathan", in_days(-2)),
            owned("7", "Nathan", in_days(-5)),
        ]);
        assert_eq!(names(&scene.today()), [Some("Nathan"), Some("Zoé"), Some("Adam"), Some("Bruno"), Some("Lucas")]);
    }

    #[test]
    fn the_actions_nobody_owns_close_the_list() {
        let scene = Scene(vec![
            on("1", in_days(-3)).owner(None),
            on("2", in_days(3)).owner(None),
            on("3", in_days(3)).owner(Some("Adam")),
        ]);
        let board = scene.today();
        assert_eq!(names(&board), [Some("Adam"), None]);
        let last = board.people.last().unwrap();
        assert_eq!((last.key.as_deref(), last.open.len(), last.late), (None, 2, 1));
    }

    #[test]
    fn open_actions_come_soonest_due_first_and_undated_last() {
        let adam = |text, due: Option<Date>| Spec { due, ..undated(text) }.owner(Some("Adam"));
        let scene = Scene(vec![
            adam("sans date", None),
            adam("plus tard", Some(in_days(20))),
            adam("faite", Some(in_days(-6))).done(),
            adam("en retard", Some(in_days(-2))),
            adam("demain, réunion récente", Some(in_days(1))).meeting(at(24, 10)),
            adam("demain, réunion ancienne", Some(in_days(1))).meeting(at(19, 10)),
        ]);
        let board = scene.today();
        let person = &board.people[0];
        assert_eq!(
            scene.said(&person.open),
            ["en retard", "demain, réunion ancienne", "demain, réunion récente", "plus tard", "sans date"]
        );
        assert_eq!((person.late, person.done, person.total), (1, 1, 6));
        assert_eq!(board.people.len(), 1);
    }

    #[test]
    fn a_just_ticked_action_stays_in_place_but_counts_done() {
        let scene = Scene(vec![
            on("cochée", in_days(-1)).owner(Some("Adam")).done(),
            on("autre", in_days(3)).owner(Some("Adam")),
        ]);
        let board = scene.board(TODAY, &[0]);
        let person = &board.people[0];
        assert_eq!(scene.said(&person.open), ["cochée", "autre"]);
        assert_eq!((person.late, person.done), (1, 1));
    }

    #[test]
    fn someone_with_everything_done_keeps_a_card() {
        let scene = Scene(vec![on("faite", in_days(3)).owner(Some("Lucas")).done()]);
        let person = &scene.today().people[0];
        assert!(person.open.is_empty());
        assert_eq!((person.done, person.total), (1, 1));
    }

    #[test]
    fn a_first_name_with_an_initial_is_someone_else() {
        let scene = Scene(vec![undated("1").owner(Some("Clément B.")), undated("2").owner(Some("Clément D."))]);
        assert_eq!(scene.today().people.len(), 2);
    }

    #[test]
    fn an_owner_of_blanks_is_nobody() {
        let scene = Scene(vec![on("1", in_days(1)).owner(Some("   ")), on("2", in_days(1)).owner(Some(""))]);
        let board = scene.today();
        assert_eq!(scene.said(&board.to_complete), ["1", "2"]);
        assert_eq!(names(&board), [None]);
        assert_eq!(board.people[0].total, 2);
    }

    #[test]
    fn the_late_meetings_count_what_is_not_done_whatever_lingers() {
        let scene = Scene(vec![
            on("en retard", in_days(-1)),
            on("cochée", in_days(-2)).done(),
            on("à l’heure", in_days(1)),
            on("sans responsable", in_days(-4)).owner(None),
        ]);
        let board = scene.board(TODAY, &[1]);
        let told: Vec<(&str, usize)> =
            board.late_by_meeting.iter().map(|(meeting, late)| (meeting.as_str(), *late)).collect();
        // The one nobody owns is late all the same, though it is not in the late group.
        assert_eq!(told, [("m00", 1), ("m03", 1)]);
        // The group counts what lingers, the meetings what is not done.
        assert_eq!(board.late, 2);
    }

    #[test]
    fn minutes_not_written_hold_no_action() {
        let scene = Scene(vec![on("une", in_days(1))]);
        let mut meetings = scene.meetings();
        for (index, state) in
            [MinutesState::Writing, MinutesState::Failed, MinutesState::Unavailable].into_iter().enumerate()
        {
            meetings.push(Meeting {
                id: MeetingId::new(format!("w{index}")).unwrap(),
                minutes: state,
                ..meeting("m99")
            });
        }
        let refs: Vec<&Meeting> = meetings.iter().collect();
        let board = Board::of(&refs, &Ask { today: TODAY, lingering: Vec::new() });
        assert_eq!((board.open, board.done.len(), board.people.len()), (1, 0, 1));
    }

    #[test]
    fn no_meeting_is_an_empty_board() {
        let board = Board::of(&[], &Ask { today: TODAY, lingering: Vec::new() });
        assert_eq!((board.open, board.late), (0, 0));
        assert!(board.to_complete.is_empty() && board.groups.is_empty() && board.done.is_empty());
        assert!(board.people.is_empty() && board.late_by_meeting.is_empty());
    }

    #[test]
    fn a_lingering_action_that_no_meeting_holds_is_ignored() {
        let scene = Scene(vec![on("une", in_days(1)).done()]);
        let ask = Ask {
            today: TODAY,
            lingering: vec![key(7), ActionKey { meeting: key(0).meeting, action: ActionId::random() }],
        };
        let meetings = scene.meetings();
        let refs: Vec<&Meeting> = meetings.iter().collect();
        let board = Board::of(&refs, &ask);
        assert_eq!((board.open, board.done.len()), (0, 1));
    }

    // An action is only unique in its meeting: ticking the `0` of one must not hold the `0` of another.
    #[test]
    fn a_lingering_action_is_held_in_its_own_meeting_only() {
        let scene = Scene(vec![on("première", in_days(1)).done(), on("seconde", in_days(1)).done()]);
        let board = scene.board(TODAY, &[1]);
        assert_eq!(scene.said(&board.groups[0].actions), ["seconde"]);
        assert_eq!(scene.said(&board.done), ["première"]);
    }

    // Every action of a written meeting is told once: open ones in the groups or to complete, done ones apart.
    fn is_whole(board: &Board, scene: &Scene) -> Result<(), String> {
        let told: Vec<&Placed> = board
            .to_complete
            .iter()
            .chain(board.groups.iter().flat_map(|grouped| &grouped.actions))
            .chain(&board.done)
            .collect();
        let mut seen: Vec<(&str, &str)> =
            told.iter().map(|placed| (placed.meeting.as_str(), placed.action.as_str())).collect();
        seen.sort_unstable();
        let before = seen.len();
        seen.dedup();
        if seen.len() != before {
            return Err("an action is told twice".into());
        }
        if before != scene.0.len() {
            return Err(format!("{before} told of {}", scene.0.len()));
        }
        let in_groups: usize = board.groups.iter().map(|grouped| grouped.actions.len()).sum();
        if board.open != board.to_complete.len() + in_groups {
            return Err("open is not what the lists hold".into());
        }
        let late = board
            .groups
            .iter()
            .find(|grouped| grouped.group == DueGroup::Late)
            .map_or(0, |grouped| grouped.actions.len());
        if board.late != late {
            return Err("late is not what the late group holds".into());
        }
        Ok(())
    }

    fn drawn(draw: &mut crate::testing::Draw) -> Scene {
        let owners = [None, Some("Nathan"), Some("nathan"), Some("Zoé"), Some("Zoe"), Some("Clément B."), Some("  ")];
        let count = draw.below(40);
        Scene(
            (0..count)
                .map(|_| Spec {
                    text: "x",
                    owner: owners[draw.below(owners.len())],
                    due: (draw.below(4) != 0).then(|| in_days(draw.below(60) as i64 - 30)),
                    done: draw.below(3) == 0,
                    // Few distinct starts, so that ties are the rule.
                    meeting_at: at(1 + draw.below(5) as u8, draw.below(3) as u8),
                })
                .collect(),
        )
    }

    // Each list sorted by its own key, the totals of the people counted another way, and the board the same for every
    // order the meetings are given in.
    #[test]
    fn whatever_the_meetings_the_board_is_whole_sorted_and_the_same_in_any_order() {
        let mut draw = crate::testing::Draw(0x0061_6374_696f_6e73);
        for case in 0..crate::testing::cases(2_000) {
            let scene = drawn(&mut draw);
            let lingering: Vec<usize> = (0..scene.0.len()).filter(|_| draw.below(4) == 0).collect();
            let board = scene.board(TODAY, &lingering);
            if let Err(wrong) = is_whole(&board, &scene) {
                panic!("case {case}: {wrong}");
            }

            let sorted = |placed: &[Placed], by: &dyn Fn(&Spec) -> (Option<Date>, UtcDateTime)| {
                let keys: Vec<_> = placed
                    .iter()
                    .map(|placed| by(&scene.0[placed.meeting.as_str()[1..].parse::<usize>().unwrap()]))
                    .collect();
                keys.is_sorted()
            };
            for grouped in &board.groups {
                assert!(
                    sorted(&grouped.actions, &|spec| (spec.due, spec.meeting_at)),
                    "case {case}: a group is out of order"
                );
            }
            assert!(
                sorted(&board.to_complete, &|spec| (None, spec.meeting_at)),
                "case {case}: to complete is out of order"
            );

            // Another path to the same totals: one pass over the specs, by key.
            let mut by_key: BTreeMap<String, (usize, usize)> = BTreeMap::new();
            for spec in &scene.0 {
                let name = spec.owner.map(str::trim).filter(|name| !name.is_empty());
                let entry = by_key.entry(name.map(person::key).unwrap_or_default()).or_default();
                entry.0 += 1;
                entry.1 += usize::from(spec.done);
            }
            let totals: BTreeMap<String, (usize, usize)> = board
                .people
                .iter()
                .map(|person| (person.key.clone().unwrap_or_default(), (person.total, person.done)))
                .collect();
            assert_eq!(totals, by_key, "case {case}");

            let mut shuffled = scene.meetings();
            for at in (1..shuffled.len()).rev() {
                shuffled.swap(at, draw.below(at + 1));
            }
            let refs: Vec<&Meeting> = shuffled.iter().collect();
            let again =
                Board::of(&refs, &Ask { today: TODAY, lingering: lingering.iter().map(|index| key(*index)).collect() });
            assert_eq!(serde_json::to_string(&again).unwrap(), serde_json::to_string(&board).unwrap(), "case {case}");
        }
    }

    #[test]
    fn the_same_question_has_the_same_answer() {
        let scene = Scene(vec![on("a", in_days(-1)), on("b", in_days(2)).owner(None), undated("c").done()]);
        assert_eq!(serde_json::to_string(&scene.today()).unwrap(), serde_json::to_string(&scene.today()).unwrap());
    }

    // What an app reads: a reference with what the day decides, and nothing it holds already.
    #[test]
    fn the_board_is_told_as_the_apps_read_it() {
        let scene = Scene(vec![
            on("en retard", in_days(-2)),
            undated("à compléter").owner(None),
            on("faite", in_days(5)).owner(Some("Adam")).done(),
        ]);
        let told = serde_json::to_value(scene.today()).unwrap();
        let expected = serde_json::json!({
            "today": "2026-10-26",
            "toComplete": [{"meeting": "m01", "action": "0"}],
            "groups": [{"group": "late", "actions": [
                {"meeting": "m00", "action": "0", "group": "late", "daysLate": 2}]}],
            "done": [{"meeting": "m02", "action": "0", "group": "nextDays"}],
            "open": 2,
            "late": 1,
            "people": [
                {"key": "nathan", "name": "Nathan", "late": 1, "done": 0, "total": 1,
                    "open": [{"meeting": "m00", "action": "0", "group": "late", "daysLate": 2}]},
                {"key": "adam", "name": "Adam", "open": [], "late": 0, "done": 1, "total": 1},
                {"open": [{"meeting": "m01", "action": "0"}], "late": 0, "done": 0, "total": 1},
            ],
            "lateByMeeting": {"m00": 1},
        });
        assert_eq!(told, expected);
    }

    #[test]
    fn a_question_needs_its_day_and_names_its_actions_with_valid_identifiers() {
        let good = r#"{"today":"2026-10-26","lingering":[{"meeting":"recorded-1","action":"2"}]}"#;
        let ask: Ask = serde_json::from_str(good).unwrap();
        assert_eq!(ask.today, TODAY);
        assert_eq!(ask.lingering.len(), 1);
        let left_out: Ask = serde_json::from_str(r#"{"today":"2026-10-26"}"#).unwrap();
        assert!(left_out.lingering.is_empty());
        for wrong in [
            "{}",
            r#"{"lingering":[]}"#,
            r#"{"today":null}"#,
            r#"{"today":""}"#,
            r#"{"today":"2026-02-30"}"#,
            r#"{"today":"26/10/2026"}"#,
            r#"{"today":20261026}"#,
            r#"{"today":"2026-10-26","lingering":[{"meeting":"../x","action":"2"}]}"#,
            r#"{"today":"2026-10-26","lingering":[{"meeting":"recorded-1"}]}"#,
            r#"{"today":"2026-10-26","lingering":"all"}"#,
        ] {
            assert!(serde_json::from_str::<Ask>(wrong).is_err(), "{wrong}");
        }
    }

    // A meeting of its own with several actions, `0`, `1`, … in the order of its minutes.
    fn meeting_with(id: &str, started_at: UtcDateTime, specs: &[Spec]) -> Meeting {
        let action = |(rank, spec): (usize, &Spec)| Action {
            id: ActionId::of_rank(rank),
            text: spec.text.into(),
            owner: spec.owner.map(Into::into),
            due: spec.due,
            done: spec.done,
            reminder: false,
            source: None,
            extra: Extra::new(),
        };
        let actions = specs.iter().enumerate().map(action).collect();
        let minutes = Minutes { summary: String::new(), decisions: Vec::new(), actions, extra: Extra::new() };
        Meeting {
            id: MeetingId::new(id).unwrap(),
            started_at,
            minutes: MinutesState::Written { minutes },
            ..meeting("m99")
        }
    }

    fn board_of(meetings: &[Meeting]) -> Board {
        let refs: Vec<&Meeting> = meetings.iter().collect();
        Board::of(&refs, &Ask { today: TODAY, lingering: Vec::new() })
    }

    // The text of each action a list holds, found in the meetings.
    fn said_in(meetings: &[Meeting], placed: &[Placed]) -> Vec<String> {
        let text = |placed: &Placed| {
            let meeting = meetings.iter().find(|meeting| meeting.id == placed.meeting).unwrap();
            let MinutesState::Written { minutes } = &meeting.minutes else { panic!("minutes not written") };
            minutes.actions.iter().find(|action| action.id == placed.action).unwrap().text.clone()
        };
        placed.iter().map(text).collect()
    }

    #[test]
    fn inside_a_meeting_the_actions_keep_the_order_of_its_minutes() {
        let owned = |text, owner| Spec { owner, ..undated(text) };
        let specs = [
            Spec { due: Some(in_days(3)), ..owned("a", Some("clement")) },
            Spec { due: Some(in_days(3)), ..owned("b", Some("Clément")) },
            owned("c", None),
            owned("d", Some("Adam")),
            owned("e", Some("Adam")).done(),
            owned("f", Some("Adam")).done(),
        ];
        let meetings = [meeting_with("m00", at(20, 10), &specs)];
        let board = board_of(&meetings);
        assert_eq!(said_in(&meetings, &board.groups[0].actions), ["a", "b"]);
        assert_eq!(said_in(&meetings, &board.to_complete), ["c", "d"]);
        assert_eq!(said_in(&meetings, &board.done), ["e", "f"]);
        let by_name = |name: &str| board.people.iter().find(|person| person.name.as_deref() == Some(name)).unwrap();
        // Two spellings in one meeting: the first of its minutes is the one kept.
        assert_eq!(said_in(&meetings, &by_name("clement").open), ["a", "b"]);
        assert_eq!(said_in(&meetings, &by_name("Adam").open), ["d"]);
    }

    // A damaged file, or one another app wrote, may hold an identifier twice: the board tells the first, the one an
    // edit reaches, so that no reference names two actions.
    #[test]
    fn an_identifier_held_twice_in_a_meeting_is_told_once() {
        let specs = [on("première", in_days(1)), on("seconde", in_days(-1))];
        let mut meetings = [meeting_with("m00", at(20, 10), &specs)];
        let MinutesState::Written { minutes } = &mut meetings[0].minutes else { panic!("minutes not written") };
        minutes.actions[1].id = ActionId::of_rank(0);
        let board = board_of(&meetings);
        assert_eq!(said_in(&meetings, &board.groups[0].actions), ["première"]);
        assert_eq!((board.groups.len(), board.open, board.late), (1, 1, 0));
        assert!(board.late_by_meeting.is_empty());
        assert_eq!((board.people.len(), board.people[0].open.len(), board.people[0].total), (1, 1, 1));
    }

    // Meetings with a few actions each, a few distinct starts so that ties are the rule: every action told once, each
    // list sorted by its own key, and the same board for any order of the meetings. Now and then an identifier is held
    // twice in a meeting, as in a damaged file.
    #[test]
    fn meetings_with_many_actions_give_a_whole_sorted_board_in_any_order() {
        let mut draw = crate::testing::Draw(0x006d_616e_7920_6163);
        let owners = [None, Some("Adam"), Some("adam"), Some("Zoé"), Some("  ")];
        for case in 0..crate::testing::cases(1_000) {
            let meetings: Vec<Meeting> = (0..1 + draw.below(6))
                .map(|index| {
                    let specs: Vec<Spec> = (0..draw.below(8))
                        .map(|_| Spec {
                            text: "x",
                            owner: owners[draw.below(owners.len())],
                            due: (draw.below(3) != 0).then(|| in_days(draw.below(30) as i64 - 15)),
                            done: draw.below(3) == 0,
                            meeting_at: at(1, 0),
                        })
                        .collect();
                    let mut meeting = meeting_with(&format!("m{index:02}"), at(1 + draw.below(3) as u8, 0), &specs);
                    if let MinutesState::Written { minutes } = &mut meeting.minutes
                        && minutes.actions.len() > 1
                        && draw.below(4) == 0
                    {
                        let from = draw.below(minutes.actions.len() - 1);
                        let to = from + 1 + draw.below(minutes.actions.len() - 1 - from);
                        minutes.actions[to].id = minutes.actions[from].id.clone();
                    }
                    meeting
                })
                .collect();
            let total: usize = meetings
                .iter()
                .map(|meeting| match &meeting.minutes {
                    MinutesState::Written { minutes } => {
                        minutes.actions.iter().map(|action| &action.id).collect::<HashSet<_>>().len()
                    }
                    _ => 0,
                })
                .sum();
            let board = board_of(&meetings);
            let told: Vec<&Placed> = board
                .to_complete
                .iter()
                .chain(board.groups.iter().flat_map(|grouped| &grouped.actions))
                .chain(&board.done)
                .collect();
            let mut seen: Vec<(&str, &str)> = told.iter().map(|p| (p.meeting.as_str(), p.action.as_str())).collect();
            seen.sort_unstable();
            seen.dedup();
            assert_eq!((seen.len(), told.len()), (total, total), "case {case}");

            // Each list by the key of the ADR, read from the meetings themselves.
            let place = |placed: &Placed| {
                let meeting = meetings.iter().find(|meeting| meeting.id == placed.meeting).unwrap();
                let MinutesState::Written { minutes } = &meeting.minutes else { panic!("minutes not written") };
                let action = minutes.actions.iter().find(|action| action.id == placed.action).unwrap();
                let rank = minutes.actions.iter().position(|other| other.id == action.id).unwrap();
                (action.due, meeting.started_at, meeting.id.clone(), rank)
            };
            for grouped in &board.groups {
                assert!(grouped.actions.iter().map(place).is_sorted(), "case {case}: a group is out of order");
            }
            let key = |placed: &Placed| {
                let (_, started_at, id, rank) = place(placed);
                (started_at, id, rank)
            };
            assert!(board.to_complete.iter().map(key).is_sorted(), "case {case}: to complete is out of order");
            let recent = |placed: &Placed| {
                let (_, started_at, id, rank) = place(placed);
                (Reverse(started_at), id, rank)
            };
            assert!(board.done.iter().map(recent).is_sorted(), "case {case}: done is out of order");
            for person in &board.people {
                let open = |placed: &Placed| {
                    let (due, started_at, id, rank) = place(placed);
                    (due.is_none(), due, started_at, id, rank)
                };
                assert!(person.open.iter().map(open).is_sorted(), "case {case}: a person is out of order");
            }

            let mut shuffled = meetings.clone();
            for at in (1..shuffled.len()).rev() {
                shuffled.swap(at, draw.below(at + 1));
            }
            let again = board_of(&shuffled);
            assert_eq!(serde_json::to_string(&again).unwrap(), serde_json::to_string(&board).unwrap(), "case {case}");
        }
    }
}
