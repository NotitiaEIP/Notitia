mod index;
mod store;

use std::cmp::Ordering;
use std::collections::{BTreeSet, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::{fmt, io, mem};

use time::UtcDateTime;

use crate::meeting::{Change, Meeting, MeetingId, Stamp, TranscriptLine};
use store::Store;

// Called on the thread that asked for the change, once the meetings have changed and before their file is written:
// a tick shows at once. It may read a meeting back. It must not ask for a change, which would wait for itself.
pub trait Listener: Send + Sync {
    fn on_event(&self, event: &Event);
}

// A meeting comes without its lines: the list, the actions and the reminders do not read them.
#[cfg_attr(test, derive(Clone, Debug, PartialEq))]
#[expect(clippy::large_enum_variant, reason = "an event lives for one call: a box would only add an allocation")]
pub enum Event {
    // Every meeting, the latest first: once the folder is read, and again when samples are lent.
    Listed(Vec<Meeting>),
    // New when the app does not know it yet.
    Changed(Meeting),
    Removed(Vec<MeetingId>),
    // The meetings whose file is behind what the app shows. Empty once every one is written.
    Unsaved(Vec<MeetingId>),
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
        listener.on_event(&Event::Listed(meetings.list()));
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
            self.listener.on_event(&event);
            behind.extend(files);
        }
        // The files left behind by an earlier failure, a full disk say, are tried again with each change.
        behind.retain(|id| self.reconcile(id).is_err());
        if *behind != were_behind {
            self.listener.on_event(&Event::Unsaved(behind.iter().cloned().collect()));
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
                let entry = self.entries.iter_mut().find(|entry| entry.meeting.id == meeting)?;
                let changed = edit.apply(&mut entry.meeting, stamp);
                changed.then(|| (Event::Changed(entry.meeting.clone()), Vec::from_iter(entry.file())))
            }
            Change::Delete { meetings } => {
                let (gone, kept): (Vec<Entry>, Vec<Entry>) =
                    mem::take(&mut self.entries).into_iter().partition(|entry| meetings.contains(&entry.meeting.id));
                self.entries = kept;
                self.deleted.extend(meetings);
                let files = gone.iter().filter_map(Entry::file).collect();
                let ids: Vec<MeetingId> = gone.into_iter().map(|entry| entry.meeting.id).collect();
                (!ids.is_empty()).then_some((Event::Removed(ids), files))
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
                let told = (Event::Changed(entry.meeting.clone()), Vec::from_iter(entry.file()));
                self.entries.push(entry);
                self.sort();
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
                (self.entries.len() > known).then(|| (Event::Listed(self.list()), Vec::new()))
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
    use std::{fs, thread};

    use tempfile::TempDir;
    use time::macros::utc_datetime;

    use super::*;
    use crate::meeting::testing::{heard, id, line, meeting};
    use crate::meeting::{ActionDraft, ActionId, Edit, MinutesDraft, MinutesState, Snapshot};

    const NOW: UtcDateTime = utc_datetime!(2026-11-11 8:00);
    const LONG_BEFORE: UtcDateTime = utc_datetime!(2026-11-10 8:00);
    const V1_BY_KOTLIN: &str = include_str!("../tests/fixtures/meeting-v1.notitia");

    // The side of the app, in a few lines: its list is rebuilt from the events alone.
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
                Event::Listed(meetings) => {
                    self.index_when_listed.lock().unwrap().push(self.folder.join("index").exists());
                    list.clone_from(meetings);
                }
                Event::Changed(meeting) => {
                    self.files_when_told.lock().unwrap().push(on_disk(&self.folder, &meeting.id));
                    list.retain(|known| known.id != meeting.id);
                    list.push(meeting.clone());
                    list.sort_by(latest_first);
                }
                Event::Removed(ids) => list.retain(|known| !ids.contains(&known.id)),
                Event::Unsaved(ids) => self.unsaved.lock().unwrap().clone_from(ids),
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
        let removed = Event::Removed(vec![id("recorded-1"), id("recorded-3"), id("sample-1")]);
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
}
