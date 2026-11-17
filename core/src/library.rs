mod index;
mod store;

use std::cmp::Ordering;
use std::collections::{BTreeSet, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::{fmt, io, mem};

use serde::Serialize;
use time::UtcDateTime;

use crate::meeting::{Change, Meeting, MeetingId, Stamp, TranscriptLine};
use store::Store;

// Called on the thread that asked for the change, once the meetings have changed and before their file is written:
// a tick shows at once. It may read a meeting back. It must not ask for a change, which would wait for itself.
pub trait Listener: Send + Sync {
    fn on_event(&self, event: &Event);
}

// A meeting comes without its lines: the list, the actions and the reminders do not read them. Crosses the bridge
// as text, written here as it stands: tests/fixtures/bridge/events.json holds one of each kind, which every app
// reads in its own tests.
#[derive(Serialize)]
#[cfg_attr(test, derive(Clone, Debug, PartialEq))]
#[expect(clippy::large_enum_variant, reason = "an event lives for one call: a box would only add an allocation")]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Event {
    // Every meeting, the latest first: once the folder is read, and again when samples are lent.
    Listed { meetings: Vec<Meeting> },
    // New when the app does not know it yet. `at` is its place in the list once changed: no app sorts.
    Changed { meeting: Meeting, at: usize },
    Removed { meetings: Vec<MeetingId> },
    // The meetings whose file is behind what the app shows. Empty once every one is written.
    Unsaved { meetings: Vec<MeetingId> },
}

#[derive(Debug, PartialEq)]
pub enum LibraryError {
    NotFound,
    // The folder or the file of the meeting.
    Unreadable,
}

impl fmt::Display for LibraryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            LibraryError::NotFound => "no meeting has this identifier",
            LibraryError::Unreadable => "the meetings cannot be read",
        })
    }
}

impl std::error::Error for LibraryError {}

// Where the lines of a meeting are. The rest of it is always in memory.
enum Transcript {
    // In its file, read when the meeting is opened.
    Written,
    // Ahead of the file, a recording say: held until a write succeeds.
    Unwritten(Vec<TranscriptLine>),
    // A sample: never written.
    Lent(Vec<TranscriptLine>),
}

struct Entry {
    // Without its lines.
    meeting: Meeting,
    transcript: Transcript,
}

struct Meetings {
    // The latest first.
    entries: Vec<Entry>,
    // For as long as the app runs: a meeting deleted while it is transcribed must not come back with its next
    // snapshot.
    deleted: HashSet<MeetingId>,
}

type Clock = Box<dyn Fn() -> UtcDateTime + Send + Sync>;

// The meetings of a folder: the one place that holds them, changes them and writes them.
pub struct Library {
    store: Store,
    device: String,
    listener: Arc<dyn Listener>,
    clock: Clock,
    // The meetings whose file is behind. Held from a change to its file, so one change is made at a time: the
    // events and the files follow the order of the changes.
    behind: Mutex<BTreeSet<MeetingId>>,
    // Held only to read or to change a meeting, never across the disk or the listener: opening a meeting does not
    // wait for a write.
    meetings: Mutex<Meetings>,
}

impl Library {
    pub fn open(directory: impl Into<PathBuf>, listener: Arc<dyn Listener>) -> Result<Self, LibraryError> {
        Self::open_at(directory.into(), listener, Box::new(UtcDateTime::now))
    }

    fn open_at(directory: PathBuf, listener: Arc<dyn Listener>, clock: Clock) -> Result<Self, LibraryError> {
        let read = |_: io::Error| LibraryError::Unreadable;
        let store = Store::open(directory).map_err(read)?;
        let device = store.device().map_err(read)?;
        let (stored, index) = store.load(clock()).map_err(read)?;
        let entries =
            stored.into_iter().map(|meeting| Entry { meeting: meeting.interrupted(), transcript: Transcript::Written });
        let mut meetings = Meetings { entries: entries.collect(), deleted: HashSet::new() };
        meetings.sort();
        listener.on_event(&Event::Listed { meetings: meetings.list() });
        // After the list: the first screen does not wait for a cache to reach the disk.
        if let Some(index) = index {
            store.keep(index);
        }
        Ok(Self { store, device, listener, clock, behind: Mutex::default(), meetings: Mutex::new(meetings) })
    }

    // The whole meeting, lines included.
    pub fn meeting(&self, id: &MeetingId) -> Result<Meeting, LibraryError> {
        self.whole(id).ok_or(LibraryError::NotFound)?.map_err(|_| LibraryError::Unreadable)
    }

    // Says nothing back: what changed, and a file that could not be written, come as events. A change that leaves
    // the meetings as they were tells nothing.
    pub fn apply(&self, change: Change) {
        let mut behind = lock(&self.behind);
        let were_behind = behind.clone();
        let stamp = Stamp { at: (self.clock)(), device: &self.device };
        let changed = lock(&self.meetings).apply(change, stamp);
        if let Some((event, files)) = changed {
            // Before the listener is told: one that panics leaves the files to the next change, never unwritten.
            behind.extend(files);
            self.listener.on_event(&event);
        }
        // The files left behind by an earlier failure, a full disk say, are tried again with each change.
        behind.retain(|id| self.reconcile(id).is_err());
        if *behind != were_behind {
            self.listener.on_event(&Event::Unsaved { meetings: behind.iter().cloned().collect() });
        }
    }

    // The disk follows the memory: the file is written when the meeting is kept, removed when it is gone. Nothing
    // is replayed, so a write that failed can never overwrite a later one.
    fn reconcile(&self, id: &MeetingId) -> io::Result<()> {
        match self.whole(id) {
            Some(meeting) => {
                self.store.write(&meeting?)?;
                lock(&self.meetings).written(id);
                Ok(())
            }
            None => self.store.remove(id),
        }
    }

    // None when the meeting is not kept. Lines that cannot be read are an error, never no line: the file is not
    // rewritten without them.
    fn whole(&self, id: &MeetingId) -> Option<io::Result<Meeting>> {
        let (meeting, held) = lock(&self.meetings).held(id)?;
        let lines = match held {
            Some(lines) => Ok(lines),
            None => self.store.read(id).map(|file| file.transcript),
        };
        Some(lines.map(|transcript| Meeting { transcript, ..meeting }))
    }
}

// Then by identifier: the order of the folder is the system's, not ours.
fn latest_first(a: &Meeting, b: &Meeting) -> Ordering {
    b.started_at.cmp(&a.started_at).then_with(|| a.id.cmp(&b.id))
}

// A listener of the app that panics must not lock the meetings away: nothing is left half changed under a lock.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Meetings {
    // What to tell, and the meetings whose file must follow. None when nothing changed.
    fn apply(&mut self, change: Change, stamp: Stamp) -> Option<(Event, Vec<MeetingId>)> {
        match change {
            Change::Edit { meeting, edit } => {
                let (at, entry) = self.entries.iter_mut().enumerate().find(|(_, entry)| entry.meeting.id == meeting)?;
                let changed = edit.apply(&mut entry.meeting, stamp);
                changed.then(|| (Event::Changed { meeting: entry.meeting.clone(), at }, Vec::from_iter(entry.file())))
            }
            Change::Delete { meetings } => {
                let (gone, kept): (Vec<Entry>, Vec<Entry>) =
                    mem::take(&mut self.entries).into_iter().partition(|entry| meetings.contains(&entry.meeting.id));
                self.entries = kept;
                self.deleted.extend(meetings);
                let files = gone.iter().filter_map(Entry::file).collect();
                let meetings: Vec<MeetingId> = gone.into_iter().map(|entry| entry.meeting.id).collect();
                (!meetings.is_empty()).then_some((Event::Removed { meetings }, files))
            }
            Change::Record(snapshot) => {
                if self.deleted.contains(&snapshot.id) {
                    return None;
                }
                let at = self.entries.iter().position(|entry| entry.meeting.id == snapshot.id);
                let known = at.map(|at| self.entries.remove(at));
                let lent = matches!(known, Some(Entry { transcript: Transcript::Lent(_), .. }));
                let (meeting, lines) = snapshot.merge(known.map(|entry| entry.meeting), stamp);
                let transcript = if lent { Transcript::Lent(lines) } else { Transcript::Unwritten(lines) };
                let entry = Entry { meeting, transcript };
                let at = self.entries.partition_point(|kept| latest_first(&kept.meeting, &entry.meeting).is_lt());
                let told = (Event::Changed { meeting: entry.meeting.clone(), at }, Vec::from_iter(entry.file()));
                self.entries.insert(at, entry);
                Some(told)
            }
            Change::Lend { meetings } => {
                let known = self.entries.len();
                for mut meeting in meetings {
                    // The user's own meeting wins over a sample of the same name, and a sample deleted stays so.
                    if !self.deleted.contains(&meeting.id)
                        && self.entries.iter().all(|entry| entry.meeting.id != meeting.id)
                    {
                        let lines = mem::take(&mut meeting.transcript);
                        self.entries.push(Entry { meeting, transcript: Transcript::Lent(lines) });
                    }
                }
                self.sort();
                (self.entries.len() > known).then(|| (Event::Listed { meetings: self.list() }, Vec::new()))
            }
        }
    }

    fn sort(&mut self) {
        self.entries.sort_by(|a, b| latest_first(&a.meeting, &b.meeting));
    }

    fn list(&self) -> Vec<Meeting> {
        self.entries.iter().map(|entry| entry.meeting.clone()).collect()
    }

    // The meeting and, when they are not in its file, its lines.
    fn held(&self, id: &MeetingId) -> Option<(Meeting, Option<Vec<TranscriptLine>>)> {
        let entry = self.entries.iter().find(|entry| &entry.meeting.id == id)?;
        let lines = match &entry.transcript {
            Transcript::Written => None,
            Transcript::Unwritten(lines) | Transcript::Lent(lines) => Some(lines.clone()),
        };
        Some((entry.meeting.clone(), lines))
    }

    // Its lines are in the file now: they leave the memory.
    fn written(&mut self, id: &MeetingId) {
        let entry = self.entries.iter_mut().find(|entry| &entry.meeting.id == id);
        if let Some(Entry { transcript: transcript @ Transcript::Unwritten(_), .. }) = entry {
            *transcript = Transcript::Written;
        }
    }
}

impl Entry {
    // The file to bring up to date after a change: none for a sample.
    fn file(&self) -> Option<MeetingId> {
        match self.transcript {
            Transcript::Lent(_) => None,
            Transcript::Written | Transcript::Unwritten(_) => Some(self.meeting.id.clone()),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::panic::{self, AssertUnwindSafe};
    use std::{fs, thread};

    use serde_json::Value;
    use tempfile::TempDir;
    use time::macros::{date, utc_datetime};

    use super::*;
    use crate::meeting::testing::{heard, id, line, meeting};
    use crate::meeting::{
        Action, ActionDraft, ActionId, ActionSource, Edit, Extra, Minutes, MinutesDraft, MinutesState, Snapshot,
    };

    const NOW: UtcDateTime = utc_datetime!(2026-11-11 8:00);
    const LONG_BEFORE: UtcDateTime = utc_datetime!(2026-11-10 8:00);
    const V1_BY_KOTLIN: &str = include_str!("../tests/fixtures/meeting-v1.notitia");
    // One event of each kind, as the apps read them.
    const FOR_THE_APPS: &str = include_str!("../tests/fixtures/bridge/events.json");

    // The side of the app, in a few lines: its list is rebuilt from the events alone, and never sorted.
    #[derive(Default)]
    struct App {
        folder: PathBuf,
        events: Mutex<Vec<Event>>,
        list: Mutex<Vec<Meeting>>,
        unsaved: Mutex<Vec<MeetingId>>,
        // The file of a meeting as it stood when its change was told.
        files_when_told: Mutex<Vec<Option<Meeting>>>,
        // Whether the folder had its index each time the list was told.
        index_when_listed: Mutex<Vec<bool>>,
    }

    impl Listener for App {
        fn on_event(&self, event: &Event) {
            self.events.lock().unwrap().push(event.clone());
            let mut list = self.list.lock().unwrap();
            match event {
                Event::Listed { meetings } => {
                    self.index_when_listed.lock().unwrap().push(self.folder.join("index").exists());
                    list.clone_from(meetings);
                }
                Event::Changed { meeting, at } => {
                    self.files_when_told.lock().unwrap().push(on_disk(&self.folder, &meeting.id));
                    list.retain(|known| known.id != meeting.id);
                    list.insert(*at, meeting.clone());
                }
                Event::Removed { meetings } => list.retain(|known| !meetings.contains(&known.id)),
                Event::Unsaved { meetings } => self.unsaved.lock().unwrap().clone_from(meetings),
            }
        }
    }

    fn on_disk(folder: &std::path::Path, id: &MeetingId) -> Option<Meeting> {
        Store::open(folder.into()).unwrap().read(id).ok()
    }

    struct Phone {
        folder: TempDir,
        app: Arc<App>,
        library: Library,
    }

    fn phone(stored: &[Meeting]) -> Phone {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().into()).unwrap();
        stored.iter().for_each(|meeting| store.write(meeting).unwrap());
        launch(folder)
    }

    // Its meetings written long before the launch: the next launch trusts what the index kept of them.
    fn settled(stored: &[Meeting]) -> (Phone, Store) {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().into()).unwrap();
        for meeting in stored {
            store.write(meeting).unwrap();
            store.date(&meeting.id, LONG_BEFORE);
        }
        (launch(folder), store)
    }

    fn launch(folder: TempDir) -> Phone {
        let app = Arc::new(App { folder: folder.path().into(), ..App::default() });
        let library = Library::open_at(folder.path().into(), app.clone(), Box::new(|| NOW)).unwrap();
        Phone { folder, app, library }
    }

    impl Phone {
        fn list(&self) -> Vec<Meeting> {
            self.app.list.lock().unwrap().clone()
        }

        fn ids(&self) -> Vec<String> {
            self.list().into_iter().map(|meeting| meeting.id.into()).collect()
        }

        fn unsaved(&self) -> Vec<MeetingId> {
            self.app.unsaved.lock().unwrap().clone()
        }

        fn told(&self) -> usize {
            self.app.events.lock().unwrap().len()
        }

        fn file(&self, meeting: &str) -> Option<Meeting> {
            on_disk(self.folder.path(), &id(meeting))
        }

        fn path(&self, name: &str) -> PathBuf {
            self.folder.path().join(name)
        }

        fn device(&self) -> String {
            fs::read_to_string(self.path("device")).unwrap()
        }

        fn edit(&self, meeting: &str, edit: Edit) {
            self.library.apply(Change::Edit { meeting: id(meeting), edit });
        }

        fn rename(&self, meeting: &str, title: &str) {
            self.edit(meeting, Edit::Rename { title: title.into() });
        }

        fn delete(&self, meetings: &[&str]) {
            self.library.apply(Change::Delete { meetings: meetings.iter().map(|meeting| id(meeting)).collect() });
        }

        // The recorder of a meeting begun after the stored ones.
        fn record(&self, lines: &[&str], minutes: MinutesDraft) {
            self.library.apply(Change::Record(Snapshot {
                id: id("recorded-9"),
                started_at: utc_datetime!(2026-11-11 7:00),
                duration_ms: 60_000,
                transcript: lines.iter().map(|text| line(0, text)).collect(),
                minutes,
            }));
        }

        // The app is killed, then opened again on the same folder.
        fn relaunched(self) -> Phone {
            drop(self.library);
            launch(self.folder)
        }
    }

    fn one_action() -> MinutesDraft {
        let action = ActionDraft { text: "Préparer la démo".into(), owner: None, due: None, source: None };
        MinutesDraft::Written { summary: String::new(), decisions: Vec::new(), actions: vec![action] }
    }

    fn first() -> ActionId {
        ActionId::new("0").unwrap()
    }

    fn done(meeting: &Meeting) -> Vec<bool> {
        let MinutesState::Written { minutes } = &meeting.minutes else { panic!("not written") };
        minutes.actions.iter().map(|action| action.done).collect()
    }

    #[test]
    fn the_meetings_of_the_folder_are_listed_the_latest_first_without_their_lines() {
        let earlier = Meeting { started_at: utc_datetime!(2026-11-09 9:30), ..heard("recorded-1") };
        let phone = phone(&[meeting("recorded-3"), earlier, heard("recorded-2")]);
        assert_eq!(phone.ids(), ["recorded-2", "recorded-3", "recorded-1"]);
        assert!(phone.list().iter().all(|meeting| meeting.transcript.is_empty()));
    }

    #[test]
    fn minutes_left_writing_by_a_killed_app_have_failed() {
        let phone = phone(&[Meeting { minutes: MinutesState::Writing, ..meeting("recorded-1") }]);
        assert_eq!(phone.list()[0].minutes, MinutesState::Failed);
    }

    #[test]
    fn the_list_is_told_before_the_index_is_written() {
        let phone = phone(&[meeting("recorded-1")]);
        assert_eq!(*phone.app.index_when_listed.lock().unwrap(), [false]);
        assert!(phone.path("index").exists());
    }

    #[test]
    fn a_meeting_is_listed_from_the_index_without_its_file_being_read() {
        let killed = Meeting { minutes: MinutesState::Writing, ..heard("recorded-1") };
        let (phone, store) = settled(std::slice::from_ref(&killed));
        let listed = phone.list();
        assert_eq!(listed[0].minutes, MinutesState::Failed);
        // Blanked, with the size and the date it had: reading it would lose the meeting.
        let size = fs::metadata(phone.path("recorded-1.notitia")).unwrap().len();
        fs::write(phone.path("recorded-1.notitia"), " ".repeat(usize::try_from(size).unwrap())).unwrap();
        store.date(&killed.id, LONG_BEFORE);
        assert_eq!(phone.relaunched().list(), listed);
    }

    #[test]
    fn a_change_made_by_the_app_overrules_the_index_at_the_next_launches() {
        let (phone, store) = settled(&[heard("recorded-1")]);
        phone.rename("recorded-1", "Point produit");
        // Dated as the file the index knows, so that its size alone tells the two apart.
        store.date(&id("recorded-1"), LONG_BEFORE);
        let shown = phone.list();
        let phone = phone.relaunched();
        assert_eq!(phone.list(), shown);
        assert_eq!(phone.relaunched().list(), shown);
    }

    #[test]
    fn a_folder_that_cannot_be_made_is_an_error() {
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("taken"), "").unwrap();
        let opened = Library::open(folder.path().join("taken").join("meetings"), Arc::new(App::default()));
        assert_eq!(opened.err(), Some(LibraryError::Unreadable));
    }

    #[test]
    fn a_change_is_told_before_its_file_is_written_and_keeps_its_lines() {
        let phone = phone(&[heard("recorded-1")]);
        phone.rename("recorded-1", "  Point produit  ");
        let renamed = Meeting {
            title: Some("Point produit".into()),
            modified_at: NOW,
            device: phone.device(),
            ..meeting("recorded-1")
        };
        assert_eq!(phone.list(), std::slice::from_ref(&renamed));
        assert_eq!(*phone.app.files_when_told.lock().unwrap(), [Some(heard("recorded-1"))]);
        assert_eq!(phone.file("recorded-1"), Some(Meeting { transcript: heard("recorded-1").transcript, ..renamed }));
        assert_eq!(phone.told(), 2);
    }

    #[test]
    fn a_change_that_changes_nothing_tells_and_writes_nothing() {
        let phone = phone(&[meeting("recorded-1")]);
        let written = fs::metadata(phone.path("recorded-1.notitia")).unwrap().modified().unwrap();
        phone.rename("recorded-1", "   ");
        phone.rename("recorded-7", "Point produit");
        phone.edit("recorded-1", Edit::SetDone { action: first(), done: false });
        phone.delete(&["recorded-7"]);
        phone.library.apply(Change::Lend { meetings: vec![heard("recorded-1")] });
        assert_eq!(phone.told(), 1);
        assert_eq!(fs::metadata(phone.path("recorded-1.notitia")).unwrap().modified().unwrap(), written);
    }

    #[test]
    fn the_whole_meeting_is_read_with_its_lines() {
        let phone = phone(&[heard("recorded-1")]);
        assert_eq!(phone.library.meeting(&id("recorded-1")), Ok(heard("recorded-1")));
        assert_eq!(phone.library.meeting(&id("recorded-7")), Err(LibraryError::NotFound));
        fs::write(phone.path("recorded-1.notitia"), "{\"version\":").unwrap();
        assert_eq!(phone.library.meeting(&id("recorded-1")), Err(LibraryError::Unreadable));
    }

    #[test]
    fn a_meeting_is_kept_as_it_is_recorded_and_its_title_is_not_taken_back() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.record(&["Bonjour à tous."], MinutesDraft::Writing);
        assert_eq!(phone.ids(), ["recorded-9", "recorded-1"]);
        assert_eq!(phone.file("recorded-9").unwrap().transcript.len(), 1);
        phone.rename("recorded-9", "Point produit");
        phone.record(&["Bonjour à tous.", "Je prépare la démo."], one_action());
        let recorded = phone.file("recorded-9").unwrap();
        assert_eq!((recorded.title.as_deref(), recorded.transcript.len()), (Some("Point produit"), 2));
        assert_eq!((recorded.device.clone(), done(&recorded)), (phone.device(), vec![false]));
        let shown = phone.list();
        assert_eq!(phone.relaunched().list(), shown);
    }

    #[test]
    fn a_change_tells_the_place_of_its_meeting_in_the_list() {
        let earlier = Meeting { started_at: utc_datetime!(2026-11-09 9:30), ..meeting("recorded-1") };
        let phone = phone(&[earlier, meeting("recorded-3")]);
        // Begun when recorded-3 was: the identifier then decides.
        phone.library.apply(Change::Record(Snapshot {
            id: id("recorded-5"),
            started_at: meeting("recorded-3").started_at,
            duration_ms: 60_000,
            transcript: Vec::new(),
            minutes: MinutesDraft::Writing,
        }));
        phone.rename("recorded-1", "Point produit");
        let place = |event: &Event| if let Event::Changed { at, .. } = event { Some(*at) } else { None };
        assert_eq!(phone.app.events.lock().unwrap().iter().filter_map(place).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(phone.ids(), ["recorded-3", "recorded-5", "recorded-1"]);
    }

    #[test]
    fn a_meeting_deleted_while_it_is_transcribed_does_not_come_back() {
        let phone = phone(&[]);
        phone.record(&["Bonjour à tous."], MinutesDraft::Writing);
        phone.delete(&["recorded-9"]);
        phone.record(&["Bonjour à tous.", "Je prépare la démo."], one_action());
        assert_eq!(phone.list(), []);
        assert_eq!(phone.relaunched().list(), []);
    }

    #[test]
    fn deleted_meetings_leave_the_list_and_the_disk_together() {
        let phone = phone(&[meeting("recorded-1"), meeting("recorded-2"), meeting("recorded-3")]);
        phone.library.apply(Change::Lend { meetings: vec![meeting("sample-1")] });
        phone.delete(&["recorded-1", "recorded-3", "sample-1"]);
        assert_eq!(phone.ids(), ["recorded-2"]);
        let removed = Event::Removed { meetings: vec![id("recorded-1"), id("recorded-3"), id("sample-1")] };
        assert_eq!(phone.app.events.lock().unwrap().last(), Some(&removed));
        assert_eq!(phone.unsaved(), []);
        assert_eq!(phone.relaunched().ids(), ["recorded-2"]);
    }

    #[test]
    fn a_lent_meeting_is_changed_like_the_others_and_never_written() {
        let phone = phone(&[meeting("recorded-1")]);
        let named = Meeting { title: Some("Exemple".into()), ..meeting("recorded-1") };
        let sample = Meeting { started_at: utc_datetime!(2026-11-11 7:00), ..heard("sample-1") };
        phone.library.apply(Change::Lend { meetings: vec![sample.clone(), named] });
        assert_eq!(phone.list(), [Meeting { transcript: Vec::new(), ..sample.clone() }, meeting("recorded-1")]);
        phone.edit("sample-1", Edit::SetDone { action: first(), done: true });
        let changed = phone.library.meeting(&id("sample-1")).unwrap();
        assert_eq!((done(&changed), changed.transcript), (vec![true], sample.transcript));
        assert_eq!((phone.file("sample-1"), phone.unsaved()), (None, vec![]));
        assert_eq!(phone.relaunched().ids(), ["recorded-1"]);
    }

    #[test]
    fn a_write_that_fails_is_told_and_done_again_with_the_next_change() {
        let phone = phone(&[heard("recorded-1"), meeting("recorded-2")]);
        // A folder where the draft goes: the disk refuses the write, whoever runs the tests.
        fs::create_dir(phone.path("recorded-1.notitia.tmp")).unwrap();
        phone.rename("recorded-1", "Point produit");
        assert_eq!(phone.list()[0].title.as_deref(), Some("Point produit"));
        assert_eq!(phone.unsaved(), [id("recorded-1")]);
        assert_eq!(phone.file("recorded-1"), Some(heard("recorded-1")));
        fs::remove_dir(phone.path("recorded-1.notitia.tmp")).unwrap();
        phone.edit("recorded-2", Edit::SetDone { action: first(), done: true });
        assert_eq!(phone.unsaved(), []);
        let written = phone.file("recorded-1").unwrap();
        assert_eq!(
            (written.title.as_deref(), written.transcript),
            (Some("Point produit"), heard("recorded-1").transcript)
        );
    }

    #[test]
    fn a_recording_that_cannot_be_written_keeps_its_lines_until_it_is() {
        let phone = phone(&[]);
        fs::create_dir(phone.path("recorded-9.notitia.tmp")).unwrap();
        phone.record(&["Bonjour à tous."], MinutesDraft::Writing);
        assert_eq!(phone.unsaved(), [id("recorded-9")]);
        assert_eq!(phone.library.meeting(&id("recorded-9")).unwrap().transcript, [line(0, "Bonjour à tous.")]);
        fs::remove_dir(phone.path("recorded-9.notitia.tmp")).unwrap();
        phone.rename("recorded-9", "Point produit");
        assert_eq!(phone.unsaved(), []);
        assert_eq!(phone.file("recorded-9"), phone.library.meeting(&id("recorded-9")).ok());
        assert_eq!(phone.file("recorded-9").unwrap().transcript.len(), 1);
    }

    #[test]
    fn a_delete_that_fails_is_done_again_with_the_next_change() {
        let phone = phone(&[meeting("recorded-1"), meeting("recorded-2")]);
        fs::remove_file(phone.path("recorded-1.notitia")).unwrap();
        fs::create_dir(phone.path("recorded-1.notitia")).unwrap();
        phone.delete(&["recorded-1"]);
        assert_eq!((phone.ids(), phone.unsaved()), (vec!["recorded-2".to_owned()], vec![id("recorded-1")]));
        fs::remove_dir(phone.path("recorded-1.notitia")).unwrap();
        phone.rename("recorded-2", "Point produit");
        assert_eq!(phone.unsaved(), []);
    }

    #[test]
    fn a_version_1_meeting_moves_to_version_2_with_its_first_change() {
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("recorded-1.notitia"), V1_BY_KOTLIN).unwrap();
        let phone = launch(folder);
        assert_eq!(fs::read_to_string(phone.path("recorded-1.notitia")).unwrap(), V1_BY_KOTLIN);
        phone.edit("recorded-1", Edit::SetDone { action: ActionId::new("1").unwrap(), done: true });
        assert!(fs::read_to_string(phone.path("recorded-1.notitia")).unwrap().starts_with("{\"version\":2,"));
        let written = phone.file("recorded-1").unwrap();
        assert_eq!((done(&written), written.transcript.len()), (vec![true, true], 2));
    }

    #[test]
    fn changes_from_several_threads_leave_one_story() {
        let phone = phone(&[heard("recorded-1")]);
        thread::scope(|scope| {
            for worker in 0..4 {
                let phone = &phone;
                scope.spawn(move || {
                    for turn in 0..25 {
                        phone.rename("recorded-1", &format!("Point {worker}-{turn}"));
                        phone.edit("recorded-1", Edit::SetDone { action: first(), done: turn % 2 == 0 });
                        assert!(phone.library.meeting(&id("recorded-1")).is_ok());
                    }
                });
            }
        });
        let whole = phone.library.meeting(&id("recorded-1")).unwrap();
        assert_eq!(phone.file("recorded-1"), Some(whole.clone()));
        assert_eq!(phone.list(), [Meeting { transcript: Vec::new(), ..whole }]);
        assert_eq!(phone.unsaved(), []);
    }

    // An app whose listener fails on a change, as a bridge does when the app throws.
    struct Failing;

    impl Listener for Failing {
        fn on_event(&self, event: &Event) {
            assert!(!matches!(event, Event::Changed { .. }), "the app failed");
        }
    }

    #[test]
    fn a_change_whose_listener_panics_is_written_with_the_next_one() {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().into()).unwrap();
        [meeting("recorded-1"), meeting("recorded-2")].iter().for_each(|meeting| store.write(meeting).unwrap());
        let library = Library::open_at(folder.path().into(), Arc::new(Failing), Box::new(|| NOW)).unwrap();
        let rename = Change::Edit { meeting: id("recorded-1"), edit: Edit::Rename { title: "Point produit".into() } };
        assert!(panic::catch_unwind(AssertUnwindSafe(|| library.apply(rename))).is_err());
        assert_eq!(on_disk(folder.path(), &id("recorded-1")), Some(meeting("recorded-1")));
        library.apply(Change::Delete { meetings: vec![id("recorded-2")] });
        assert_eq!(on_disk(folder.path(), &id("recorded-1")).unwrap().title.as_deref(), Some("Point produit"));
    }

    #[test]
    fn the_events_are_written_as_the_apps_read_them() {
        let with = |name: &str, minutes| Meeting { minutes, ..meeting(name) };
        let reviewed = Meeting {
            modified_at: NOW,
            device: "phone-2".into(),
            title: Some("Revue de sprint".into()),
            speakers: vec!["Lucas".into(), "Nathan".into()],
            minutes: MinutesState::Written {
                minutes: Minutes {
                    summary: "Point sur la démo.".into(),
                    decisions: vec!["La démo a lieu le 15 novembre.".into()],
                    actions: vec![
                        Action {
                            id: first(),
                            text: "Préparer la démo".into(),
                            owner: Some("Lucas".into()),
                            due: Some(date!(2026 - 11 - 14)),
                            done: true,
                            reminder: true,
                            source: Some(ActionSource {
                                quote: "Je prépare la démo.".into(),
                                speaker: Some("Nathan".into()),
                                at_ms: 724_000,
                                extra: Extra::new(),
                            }),
                            extra: Extra::new(),
                        },
                        Action {
                            id: ActionId::new("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f").unwrap(),
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
            ..meeting("recorded-1")
        };
        let events = [
            Event::Listed {
                meetings: vec![
                    with("recorded-2", MinutesState::Writing),
                    with("recorded-3", MinutesState::Failed),
                    with("recorded-4", MinutesState::Unavailable),
                ],
            },
            Event::Changed { meeting: reviewed, at: 1 },
            Event::Removed { meetings: vec![id("recorded-1"), id("recorded-3")] },
            Event::Unsaved { meetings: vec![id("recorded-1")] },
            Event::Unsaved { meetings: Vec::new() },
        ];
        assert_eq!(serde_json::to_value(events).unwrap(), serde_json::from_str::<Value>(FOR_THE_APPS).unwrap());
    }
}
