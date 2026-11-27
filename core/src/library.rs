mod index;
mod sealed;
mod store;

use std::cmp::Ordering;
use std::collections::{BTreeSet, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::{fmt, io, mem};

use serde::Serialize;
use time::UtcDateTime;

use crate::audio::{self, AudioError};
use crate::meeting::{self, Change, Meeting, MeetingId, MinutesDraft, MinutesState, Stamp, TranscriptLine};
use crate::secret::Key;
use store::{Loaded, Store};

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
    // For as long as the app runs: a meeting deleted comes back neither with the samples lent again nor from the
    // recorder.
    deleted: HashSet<MeetingId>,
}

pub(crate) type Clock = Box<dyn Fn() -> UtcDateTime + Send + Sync>;

// The meetings of a folder: the one place that holds them, changes them and writes them.
pub struct Library {
    // With the key of the app: without one, no meeting nor audio is written, and no audio is read.
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
    #[expect(clippy::disallowed_methods, reason = "the one clock of the core: every rule is handed it")]
    pub fn open(
        directory: impl Into<PathBuf>,
        key: Option<Key>,
        listener: Arc<dyn Listener>,
    ) -> Result<Self, LibraryError> {
        Self::open_at(directory.into(), key, listener, Box::new(UtcDateTime::now))
    }

    pub(crate) fn open_at(
        directory: PathBuf,
        key: Option<Key>,
        listener: Arc<dyn Listener>,
        clock: Clock,
    ) -> Result<Self, LibraryError> {
        let read = |_: io::Error| LibraryError::Unreadable;
        let store = Store::open(directory, key).map_err(read)?;
        let device = store.device().map_err(read)?;
        let Loaded { meetings: stored, index, clear } = store.load(clock()).map_err(read)?;
        // A recording the app did not live to stop lasts as long as its audio. Only an audio whose size tells
        // another length than its meeting's is opened: a launch deciphers none of three hundred meetings whose
        // minutes wait for a model.
        let cut = |meeting: &&Meeting| {
            let seconds = store.audio(&meeting.id, false).ok().and_then(|file| audio::seconds(file).ok());
            seconds.is_some_and(|seconds| seconds != meeting.duration_ms.div_ceil(1_000))
        };
        let lasted: Vec<Change> = stored
            .iter()
            .filter(|meeting| meeting.minutes == MinutesState::Writing && meeting.audio_erased_at.is_none())
            .filter(cut)
            .filter_map(|meeting| {
                let duration_ms = open_audio(&store, &meeting.id).ok()?.duration_ms();
                Some(Change::Lasted { meeting: meeting.id.clone(), duration_ms })
            })
            .collect();
        let entries = stored.into_iter().map(|meeting| Entry { meeting, transcript: Transcript::Written });
        let mut meetings = Meetings { entries: entries.collect(), deleted: HashSet::new() };
        meetings.sort();
        let stamp = Stamp { at: clock(), device: &device };
        let mut behind: BTreeSet<MeetingId> = lasted
            .into_iter()
            .filter_map(|change| meetings.apply(change, stamp))
            .flat_map(|(_, files)| files)
            .collect();
        // Written in clear before the files were sealed: sealed now, by the same path as any file behind. Without a key
        // they stay as they are, read and never written.
        if store.key().is_some() {
            behind.extend(clear);
        }
        listener.on_event(&Event::Listed { meetings: meetings.list() });
        // After the list: the first screen does not wait for a cache, nor for the meetings that lasted, to reach the
        // disk.
        if let Some(index) = index {
            store.keep(index);
        }
        let library =
            Self { store, device, listener, clock, behind: Mutex::new(behind), meetings: Mutex::new(meetings) };
        library.catch_up(&mut lock(&library.behind), &BTreeSet::new());
        Ok(library)
    }

    // The whole meeting, lines included.
    pub fn meeting(&self, id: &MeetingId) -> Result<Meeting, LibraryError> {
        self.whole(id).ok_or(LibraryError::NotFound)?.map_err(|_| LibraryError::Unreadable)
    }

    // Says nothing back: what changed, and a file that could not be written, come as events. A change that leaves
    // the meetings as they were tells nothing.
    pub fn apply(&self, change: Change) {
        self.change(change);
    }

    // Whether the meetings changed: the pipeline learns so that a window it heard was kept.
    pub(crate) fn change(&self, change: Change) -> bool {
        let mut behind = lock(&self.behind);
        let were_behind = behind.clone();
        let reads = lock(&self.meetings).reads_lines(&change).cloned();
        if let Some(meeting) = reads {
            self.hold_lines(&meeting);
        }
        let stamp = Stamp { at: (self.clock)(), device: &self.device };
        let changed = lock(&self.meetings).apply(change, stamp);
        let applied = changed.is_some();
        if let Some((event, files)) = changed {
            // Before the listener is told: one that panics leaves the files to the next change, never unwritten.
            behind.extend(files);
            self.listener.on_event(&event);
        }
        self.catch_up(&mut behind, &were_behind);
        applied
    }

    // Heard lines are added to those of the file, never to a copy the pipeline read before: a line corrected meanwhile
    // stays corrected. They are brought into memory under `behind`, so no change comes between the read and the
    // adding, and only for a change that will take them. A file that cannot be read keeps them: nothing is added.
    fn hold_lines(&self, id: &MeetingId) {
        if let Ok(file) = self.store.read(id) {
            lock(&self.meetings).hold(id, file.transcript);
        }
    }

    // The files of the meetings just changed, and those left behind by an earlier failure, a full disk say, which
    // are tried again with each change.
    fn catch_up(&self, behind: &mut BTreeSet<MeetingId>, were_behind: &BTreeSet<MeetingId>) {
        behind.retain(|id| self.reconcile(id).is_err());
        if behind != were_behind {
            self.listener.on_event(&Event::Unsaved { meetings: behind.iter().cloned().collect() });
        }
    }

    // The audio of a meeting, to add sound to: made the first time, taken up where it stopped after that. A sample
    // has none, nor a meeting the library does not keep.
    pub fn record(&self, id: &MeetingId, tracks: usize) -> Result<audio::Writer, AudioError> {
        let key = self.store.key().ok_or(AudioError::Locked)?;
        // Held as a change holds it: a meeting deleted or erased meanwhile does not get an audio back.
        let _changes = lock(&self.behind);
        if !lock(&self.meetings).records(id) {
            return Err(AudioError::Absent);
        }
        audio::Writer::open(self.store.audio(id, true)?, key, tracks)
    }

    // What `which` makes of the meetings kept in a file, in the order of the list: the latest first.
    pub(crate) fn kept<T>(&self, which: impl Fn(&Meeting) -> Option<T>) -> Vec<T> {
        let meetings = lock(&self.meetings);
        let kept = meetings.entries.iter().filter(|entry| !matches!(entry.transcript, Transcript::Lent(_)));
        kept.filter_map(|entry| which(&entry.meeting)).collect()
    }

    // Gone once marked erased, though its file may wait to be deleted: nothing reads it again.
    pub fn audio(&self, id: &MeetingId) -> Result<audio::Reader, AudioError> {
        if lock(&self.meetings).erased(id) {
            return Err(AudioError::Absent);
        }
        open_audio(&self.store, id)
    }

    // The audio of the meetings kept as long as the rule says but that of `spared`, the one being recorded: each meeting
    // is marked first, then its file deleted once `let_go` returns, when nothing reads it any more. Killed in between,
    // the next call finds it marked, its audio still there, and deletes it. A file that cannot be deleted stays marked,
    // and the next call tries again. Without the key, nothing: the mark could not be written, and the next launch would
    // find a meeting without its audio nor the day it went. Whether a meeting changed.
    pub(crate) fn erase_audio(&self, spared: Option<&MeetingId>, let_go: impl FnOnce()) -> bool {
        if !self.has_key() {
            return false;
        }
        let now = self.now();
        let expired = self.kept(|kept| {
            let due = Some(&kept.id) != spared && meeting::expired(kept.started_at, now);
            due.then(|| kept.id.clone())
        });
        // Never marked without an audio to erase: the meetings of the Kotlin app never had one.
        let erasing: Vec<MeetingId> = expired.into_iter().filter(|id| self.store.has_audio(id)).collect();
        if erasing.is_empty() {
            return false;
        }
        // Each one marked, none skipped once one has changed.
        let mut changed = false;
        for id in &erasing {
            changed |= self.change(Change::Erased { meeting: id.clone() });
        }
        let_go();
        // Held as a change holds it: no audio is made again in between.
        let _changes = lock(&self.behind);
        self.store.erase_audio(&erasing);
        changed
    }

    // Without it nothing is recorded: no audio reaches the disk in clear.
    pub(crate) fn has_key(&self) -> bool {
        self.store.key().is_some()
    }

    // A recording is dated by the clock its changes are.
    pub(crate) fn now(&self) -> UtcDateTime {
        (self.clock)()
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

fn open_audio(store: &Store, id: &MeetingId) -> Result<audio::Reader, AudioError> {
    let key = store.key().ok_or(AudioError::Locked)?;
    let file = store.audio(id, false).map_err(|error| match error.kind() {
        io::ErrorKind::NotFound => AudioError::Absent,
        _ => AudioError::Unreadable,
    })?;
    audio::Reader::open(file, key)
}

// Then by identifier: the order of the folder is the system's, not ours.
fn latest_first(a: &Meeting, b: &Meeting) -> Ordering {
    b.started_at.cmp(&a.started_at).then_with(|| a.id.cmp(&b.id))
}

// A listener of the app that panics must not lock the meetings away: nothing is left half changed under a lock.
pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Meetings {
    // What to tell, and the meetings whose file must follow. None when nothing changed.
    fn apply(&mut self, change: Change, stamp: Stamp) -> Option<(Event, Vec<MeetingId>)> {
        match change {
            Change::Edit { meeting, edit } => {
                let (at, entry) = self.find(&meeting)?;
                edit.apply(&mut entry.meeting, stamp).then(|| entry.told(at))
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
            Change::Begin { id, started_at, offset_minutes } => {
                if self.deleted.contains(&id) || self.find(&id).is_some() {
                    return None;
                }
                let meeting = Meeting::begun(id, started_at, Some(offset_minutes), stamp);
                Some(self.insert(Entry { meeting, transcript: Transcript::Unwritten(Vec::new()) }))
            }
            Change::Lasted { meeting, duration_ms } => {
                let (at, entry) = self.find(&meeting)?;
                if entry.meeting.duration_ms == duration_ms {
                    return None;
                }
                entry.meeting.duration_ms = duration_ms;
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            Change::Heard { meeting, lines, from_ms, until_ms } => {
                let (at, entry) = self.find(&meeting)?;
                // In memory by now, unless the meeting is a sample or its file could not be read.
                let Transcript::Unwritten(held) = &mut entry.transcript else { return None };
                if entry.meeting.transcribed_ms != Some(from_ms) {
                    return None;
                }
                held.extend(lines);
                entry.meeting.transcribed_ms = until_ms;
                if until_ms.is_none() {
                    // The transcript whole, its minutes are owed: written as soon as a writer is there, now or once
                    // a model comes. Never unavailable, which says what this device lacks, not what the meeting is.
                    MinutesDraft::owed(held).settle(&mut entry.meeting);
                }
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            Change::Drafted { meeting, minutes } => {
                let (at, entry) = self.find(&meeting)?;
                // Compared, then written: minutes settled meanwhile, or a meeting heard again, are not settled by a
                // draft of the transcript before.
                if !entry.owes_minutes() || matches!(minutes, MinutesDraft::Writing) {
                    return None;
                }
                minutes.settle(&mut entry.meeting);
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            Change::Rehear { meeting } => {
                let (at, entry) = self.find(&meeting)?;
                if !entry.owes_minutes() || entry.meeting.audio_erased_at.is_some() {
                    return None;
                }
                entry.meeting.transcribed_ms = Some(0);
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            Change::Rewrite { meeting } => {
                let (at, entry) = self.find(&meeting)?;
                // Only minutes that ended without being written, of a transcript whole: minutes written were perhaps
                // corrected, and a sample has no writer.
                let ended = matches!(entry.meeting.minutes, MinutesState::Failed | MinutesState::Unavailable);
                if !ended || entry.meeting.transcribed_ms.is_some() || entry.file().is_none() {
                    return None;
                }
                entry.meeting.minutes = MinutesState::Writing;
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            Change::Erased { meeting } => {
                let (at, entry) = self.find(&meeting)?;
                if entry.meeting.audio_erased_at.is_some() || entry.file().is_none() {
                    return None;
                }
                entry.meeting.audio_erased_at = Some(stamp.at);
                if entry.meeting.transcribed_ms.take().is_some() {
                    // Closed on what was heard, as the end of its audio would close it. Lines that could not be read
                    // leave its minutes owed, settled by the writer once they can.
                    let owed = match &entry.transcript {
                        Transcript::Unwritten(lines) => MinutesDraft::owed(lines),
                        Transcript::Written | Transcript::Lent(_) => MinutesDraft::Writing,
                    };
                    owed.settle(&mut entry.meeting);
                }
                stamp.mark(&mut entry.meeting);
                Some(entry.told(at))
            }
            // The session's: it reaches the meetings as one Erased each, the meeting being recorded spared.
            Change::Tidy => None,
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

    fn find(&mut self, id: &MeetingId) -> Option<(usize, &mut Entry)> {
        self.entries.iter_mut().enumerate().find(|(_, entry)| &entry.meeting.id == id)
    }

    // At its place in the list.
    fn insert(&mut self, entry: Entry) -> (Event, Vec<MeetingId>) {
        let at = self.entries.partition_point(|kept| latest_first(&kept.meeting, &entry.meeting).is_lt());
        let told = entry.told(at);
        self.entries.insert(at, entry);
        told
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

    // The meeting whose lines, only in its file, the change would add to or settle its minutes from: heard lines
    // at the edge it reached, or an erased audio that closes a transcript still heard.
    fn reads_lines<'a>(&self, change: &'a Change) -> Option<&'a MeetingId> {
        let (id, edge) = match change {
            Change::Heard { meeting, from_ms, .. } => (meeting, Some(*from_ms)),
            Change::Erased { meeting } => (meeting, None),
            _ => return None,
        };
        let entry = self.entries.iter().find(|entry| &entry.meeting.id == id)?;
        let heard = entry.meeting.transcribed_ms?;
        let takes = edge.is_none_or(|edge| edge == heard) && entry.meeting.audio_erased_at.is_none();
        (takes && matches!(entry.transcript, Transcript::Written)).then_some(id)
    }

    // The lines of its file, held until they are written again with those added.
    fn hold(&mut self, id: &MeetingId, lines: Vec<TranscriptLine>) {
        let entry = self.entries.iter_mut().find(|entry| &entry.meeting.id == id);
        if let Some(Entry { transcript: transcript @ Transcript::Written, .. }) = entry {
            *transcript = Transcript::Unwritten(lines);
        }
    }

    // Kept in a file, its audio not erased.
    fn records(&self, id: &MeetingId) -> bool {
        let entry = self.entries.iter().find(|entry| &entry.meeting.id == id);
        entry.is_some_and(|entry| entry.file().is_some() && entry.meeting.audio_erased_at.is_none())
    }

    fn erased(&self, id: &MeetingId) -> bool {
        self.entries.iter().any(|entry| &entry.meeting.id == id && entry.meeting.audio_erased_at.is_some())
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
    fn told(&self, at: usize) -> (Event, Vec<MeetingId>) {
        (Event::Changed { meeting: self.meeting.clone(), at }, Vec::from_iter(self.file()))
    }

    // Heard whole, kept in a file, its minutes writing: what the core's writer may settle.
    fn owes_minutes(&self) -> bool {
        let meeting = &self.meeting;
        meeting.minutes == MinutesState::Writing && meeting.transcribed_ms.is_none() && self.file().is_some()
    }

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
    use crate::audio::testing::chord;
    use crate::meeting::testing::{heard, id, line, meeting};
    use crate::meeting::{
        Action, ActionDraft, ActionId, ActionSource, Edit, Extra, Minutes, MinutesDraft, MinutesState,
    };
    use crate::secret::testing::key;

    const NOW: UtcDateTime = utc_datetime!(2026-11-11 8:00);
    const LONG_BEFORE: UtcDateTime = utc_datetime!(2026-11-10 8:00);
    const V1_BY_KOTLIN: &str = include_str!("../tests/fixtures/meeting-v1.notitia");
    const V2_BY_THE_CORE: &str = include_str!("../tests/fixtures/meeting-v2.notitia");
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
        Store::open(folder.into(), Some(key(1))).unwrap().read(id).ok()
    }

    struct Phone {
        folder: TempDir,
        app: Arc<App>,
        library: Library,
    }

    fn phone(stored: &[Meeting]) -> Phone {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().into(), Some(key(1))).unwrap();
        stored.iter().for_each(|meeting| store.write(meeting).unwrap());
        launch(folder)
    }

    // Its meetings written long before the launch: the next launch trusts what the index kept of them.
    fn settled(stored: &[Meeting]) -> (Phone, Store) {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().into(), Some(key(1))).unwrap();
        for meeting in stored {
            store.write(meeting).unwrap();
            store.date(&meeting.id, LONG_BEFORE);
        }
        (launch(folder), store)
    }

    fn launch(folder: TempDir) -> Phone {
        let app = Arc::new(App { folder: folder.path().into(), ..App::default() });
        let library = Library::open_at(folder.path().into(), Some(key(1)), app.clone(), Box::new(|| NOW)).unwrap();
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

        // As the recorder begins a meeting at its first sound, after the stored ones.
        fn begin(&self, meeting: &str) {
            let started_at = utc_datetime!(2026-11-11 7:00);
            self.library.apply(Change::Begin { id: id(meeting), started_at, offset_minutes: 60 });
        }

        fn lasted(&self, meeting: &str, duration_ms: u64) {
            self.library.apply(Change::Lasted { meeting: id(meeting), duration_ms });
        }

        // As the pipeline adds what it heard of a window: whether the meetings took it.
        fn heard(&self, meeting: &str, texts: &[&str], from_ms: u64, until_ms: Option<u64>) -> bool {
            let lines = texts.iter().map(|text| line(from_ms, text)).collect();
            self.library.change(Change::Heard { meeting: id(meeting), lines, from_ms, until_ms })
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
    fn minutes_owed_are_still_owed_after_a_launch() {
        // Heard whole, its minutes not written yet: the next writer takes them, whatever stopped the app.
        let owed = Meeting { minutes: MinutesState::Writing, ..heard("recorded-1") };
        let heard_on = Meeting { id: id("recorded-2"), transcribed_ms: Some(30_000), ..owed.clone() };
        let phone = phone(&[owed.clone(), heard_on.clone()]);
        assert_eq!(
            phone.relaunched().list(),
            [owed, heard_on].map(|meeting| Meeting { transcript: vec![], ..meeting })
        );
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
        assert_eq!(listed[0].minutes, MinutesState::Writing);
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
        let opened = Library::open(folder.path().join("taken").join("meetings"), None, Arc::new(App::default()));
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
    fn a_meeting_is_kept_as_it_is_recorded_and_heard_and_its_title_is_not_taken_back() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.begin("recorded-9");
        assert!(phone.heard("recorded-9", &["Bonjour à tous."], 0, Some(30_000)));
        assert_eq!(phone.ids(), ["recorded-9", "recorded-1"]);
        assert_eq!(phone.file("recorded-9").unwrap().transcript.len(), 1);
        phone.rename("recorded-9", "Point produit");
        assert!(phone.heard("recorded-9", &["Je prépare la démo."], 30_000, None));
        assert!(drafted(&phone, "recorded-9", one_action()));
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
        let started_at = meeting("recorded-3").started_at;
        phone.library.apply(Change::Begin { id: id("recorded-5"), started_at, offset_minutes: 60 });
        phone.rename("recorded-1", "Point produit");
        let place = |event: &Event| if let Event::Changed { at, .. } = event { Some(*at) } else { None };
        assert_eq!(phone.app.events.lock().unwrap().iter().filter_map(place).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(phone.ids(), ["recorded-3", "recorded-5", "recorded-1"]);
    }

    #[test]
    fn a_meeting_deleted_while_it_is_heard_does_not_come_back() {
        let phone = phone(&[]);
        phone.begin("recorded-9");
        assert!(phone.heard("recorded-9", &["Bonjour à tous."], 0, Some(30_000)));
        phone.delete(&["recorded-9"]);
        assert!(!phone.heard("recorded-9", &["Je prépare la démo."], 30_000, None));
        assert!(!drafted(&phone, "recorded-9", one_action()));
        assert!(!phone.library.change(Change::Rehear { meeting: id("recorded-9") }));
        phone.begin("recorded-9");
        assert_eq!(phone.list(), []);
        assert_eq!(phone.relaunched().list(), []);
    }

    #[test]
    fn a_meeting_begun_by_the_recorder_is_written_at_once_and_lasts_as_told() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.begin("recorded-9");
        let begun = Meeting {
            started_at: utc_datetime!(2026-11-11 7:00),
            offset_minutes: Some(60),
            duration_ms: 0,
            transcribed_ms: Some(0),
            modified_at: NOW,
            device: phone.device(),
            minutes: MinutesState::Writing,
            ..meeting("recorded-9")
        };
        assert_eq!((phone.list()[0].clone(), phone.file("recorded-9")), (begun.clone(), Some(begun)));
        phone.lasted("recorded-9", 10_000);
        let told = phone.told();
        phone.lasted("recorded-9", 10_000);
        phone.lasted("recorded-7", 10_000);
        phone.begin("recorded-9");
        assert_eq!(phone.told(), told);
        assert_eq!(phone.file("recorded-9").unwrap().duration_ms, 10_000);
        assert_eq!(phone.ids(), ["recorded-9", "recorded-1"]);
    }

    #[test]
    fn minutes_owed_of_a_meeting_never_heard_are_heard_from_its_start_first() {
        // Left by an app before the core's: its minutes owed, or asked again, and not a line.
        let unheard = |name: &str, minutes| Meeting { minutes, ..meeting(name) };
        let phone = phone(&[
            unheard("recorded-1", MinutesState::Writing),
            unheard("recorded-2", MinutesState::Failed),
            Meeting { transcribed_ms: Some(30_000), ..unheard("recorded-3", MinutesState::Writing) },
            meeting("recorded-4"),
        ]);
        let sample = Meeting { minutes: MinutesState::Writing, ..meeting("sample-1") };
        phone.library.apply(Change::Lend { meetings: vec![sample] });
        let rehear = |meeting: &str| phone.library.change(Change::Rehear { meeting: id(meeting) });
        let told = phone.told();
        for meeting in ["recorded-2", "recorded-3", "recorded-4", "sample-1", "recorded-7"] {
            assert!(!rehear(meeting), "{meeting}");
        }
        assert_eq!(phone.told(), told);
        assert!(rehear("recorded-1"));
        let owed = phone.file("recorded-1").unwrap();
        assert_eq!(
            (owed.transcribed_ms, owed.minutes.clone(), owed.modified_at),
            (Some(0), MinutesState::Writing, NOW)
        );
        assert_eq!(phone.list().iter().find(|kept| kept.id == id("recorded-1")), Some(&owed));
        // Heard from its start now: a late one changes nothing.
        assert!(!rehear("recorded-1"));
        assert!(phone.heard("recorded-1", &["Bonjour à tous."], 0, None));
        assert!(drafted(&phone, "recorded-1", one_action()));
    }

    #[test]
    fn heard_lines_are_added_after_those_of_the_file_from_the_edge_reached() {
        // Written by an app that held the meeting, its lines only in the file.
        let stored = Meeting { transcribed_ms: Some(0), minutes: MinutesState::Writing, ..heard("recorded-1") };
        let phone = phone(&[stored]);
        assert!(phone.heard("recorded-1", &["Je prépare la démo."], 0, Some(30_000)));
        let kept = phone.file("recorded-1").unwrap();
        assert_eq!(kept.transcript, [line(0, "Bonjour à tous."), line(0, "Je prépare la démo.")]);
        assert_eq!((kept.transcribed_ms, kept.modified_at), (Some(30_000), NOW));
        assert_eq!(phone.list()[0].transcribed_ms, Some(30_000));
        // The same window again, or one from another edge, is not added.
        let told = phone.told();
        assert!(!phone.heard("recorded-1", &["Je prépare la démo."], 0, Some(30_000)));
        assert!(!phone.heard("recorded-1", &["Plus tard."], 45_000, Some(61_000)));
        assert_eq!((phone.told(), phone.file("recorded-1")), (told, Some(kept)));
        let phone = phone.relaunched();
        assert!(phone.heard("recorded-1", &["Et la suite."], 30_000, None));
        let whole = phone.file("recorded-1").unwrap();
        assert_eq!(whole.transcript.len(), 3);
        // Whole, its minutes owed to the next writer.
        assert_eq!((whole.transcribed_ms, whole.minutes), (None, MinutesState::Writing));
        assert!(!phone.heard("recorded-1", &["Encore."], 30_000, None));
    }

    #[test]
    fn a_transcript_where_nothing_was_said_ends_with_empty_minutes() {
        let phone = phone(&[]);
        phone.begin("recorded-9");
        assert!(phone.library.change(Change::Heard {
            meeting: id("recorded-9"),
            lines: vec![TranscriptLine { at_ms: 0, text: None, speaker: None, extra: Extra::new() }],
            from_ms: 0,
            until_ms: None,
        }));
        let empty = Minutes { summary: String::new(), decisions: vec![], actions: vec![], extra: Extra::new() };
        assert_eq!(phone.file("recorded-9").unwrap().minutes, MinutesState::Written { minutes: empty });
    }

    fn drafted(phone: &Phone, meeting: &str, minutes: MinutesDraft) -> bool {
        phone.library.change(Change::Drafted { meeting: id(meeting), minutes })
    }

    #[test]
    fn minutes_drafted_settle_only_minutes_still_owed() {
        let owed = Meeting { minutes: MinutesState::Writing, ..heard("recorded-1") };
        let failed = Meeting { id: id("recorded-2"), minutes: MinutesState::Failed, ..owed.clone() };
        let heard_on = Meeting { id: id("recorded-3"), transcribed_ms: Some(30_000), ..owed.clone() };
        let phone = phone(&[owed, failed, heard_on, meeting("recorded-4")]);
        phone
            .library
            .apply(Change::Lend { meetings: vec![Meeting { minutes: MinutesState::Writing, ..heard("sample-1") }] });
        let told = phone.told();
        for meeting in ["recorded-2", "recorded-3", "recorded-4", "sample-1", "recorded-7"] {
            assert!(!drafted(&phone, meeting, one_action()), "{meeting}");
        }
        assert!(!drafted(&phone, "recorded-1", MinutesDraft::Writing));
        assert_eq!(phone.told(), told);
        assert!(drafted(&phone, "recorded-1", one_action()));
        let written = phone.file("recorded-1").unwrap();
        assert_eq!((written.modified_at, written.transcript.len()), (NOW, 1));
        assert_eq!(done(&written), [false]);
        // Once settled, a late draft takes nothing back.
        assert!(!drafted(&phone, "recorded-1", MinutesDraft::Failed));
    }

    #[test]
    fn minutes_that_ended_unwritten_are_owed_again_when_asked() {
        let ended = |name: &str, minutes| Meeting { id: id(name), minutes, ..heard("recorded-1") };
        let phone = phone(&[
            ended("recorded-1", MinutesState::Failed),
            ended("recorded-2", MinutesState::Unavailable),
            ended("recorded-3", MinutesState::Writing),
            meeting("recorded-4"),
            Meeting { transcribed_ms: Some(0), ..ended("recorded-5", MinutesState::Failed) },
        ]);
        phone
            .library
            .apply(Change::Lend { meetings: vec![Meeting { minutes: MinutesState::Failed, ..heard("sample-1") }] });
        let rewrite = |meeting: &str| phone.library.change(Change::Rewrite { meeting: id(meeting) });
        for meeting in ["recorded-3", "recorded-4", "recorded-5", "sample-1", "recorded-7"] {
            assert!(!rewrite(meeting), "{meeting}");
        }
        assert!(rewrite("recorded-1") && rewrite("recorded-2"));
        for meeting in ["recorded-1", "recorded-2"] {
            let owed = phone.file(meeting).unwrap();
            assert_eq!((owed.minutes, owed.modified_at, owed.transcript.len()), (MinutesState::Writing, NOW, 1));
        }
    }

    #[test]
    fn heard_lines_stay_in_memory_until_their_file_is_written() {
        let phone = phone(&[]);
        phone.begin("recorded-9");
        fs::create_dir(phone.path("recorded-9.notitia.tmp")).unwrap();
        assert!(phone.heard("recorded-9", &["Bonjour à tous."], 0, Some(20_000)));
        assert!(phone.heard("recorded-9", &["Je prépare la démo."], 20_000, Some(40_000)));
        // Held in memory, a window already added is refused all the same.
        assert!(!phone.heard("recorded-9", &["Je prépare la démo."], 20_000, Some(40_000)));
        assert_eq!(phone.unsaved(), [id("recorded-9")]);
        assert_eq!(phone.library.meeting(&id("recorded-9")).unwrap().transcript.len(), 2);
        fs::remove_dir(phone.path("recorded-9.notitia.tmp")).unwrap();
        phone.lasted("recorded-9", 40_000);
        assert_eq!(phone.unsaved(), []);
        assert_eq!(phone.file("recorded-9"), phone.library.meeting(&id("recorded-9")).ok());
    }

    #[test]
    fn the_end_of_a_transcript_keeps_minutes_already_written() {
        let phone = phone(&[Meeting { transcribed_ms: Some(0), ..heard("recorded-1") }]);
        assert!(phone.heard("recorded-1", &[], 0, None));
        assert_eq!(phone.file("recorded-1").unwrap().minutes, heard("recorded-1").minutes);
    }

    #[test]
    fn heard_lines_never_reach_a_meeting_heard_whole_a_sample_or_a_deleted_one() {
        let phone = phone(&[heard("recorded-9")]);
        let sample = Meeting { transcribed_ms: Some(0), ..heard("sample-1") };
        phone.library.apply(Change::Lend { meetings: vec![sample] });
        phone.begin("recorded-8");
        phone.delete(&["recorded-8"]);
        for meeting in ["recorded-9", "sample-1", "recorded-8", "recorded-7"] {
            assert!(!phone.heard(meeting, &["Je prépare la démo."], 0, Some(30_000)), "{meeting}");
        }
        assert_eq!(phone.library.meeting(&id("recorded-9")).unwrap().transcript, [line(0, "Bonjour à tous.")]);
        assert_eq!(phone.library.meeting(&id("sample-1")).unwrap().transcript, heard("sample-1").transcript);
        assert_eq!((phone.file("recorded-8"), phone.file("sample-1")), (None, None));
        // What the pipeline is given to hear: neither a sample nor a meeting heard whole.
        let heard = |meeting: &Meeting| meeting.transcribed_ms.map(|_| meeting.id.clone());
        assert_eq!(phone.library.kept(heard), []);
        assert_eq!(phone.library.kept(|meeting| Some(meeting.id.clone())), [id("recorded-9")]);
    }

    #[test]
    fn heard_lines_are_not_added_to_a_file_that_cannot_be_read() {
        let phone = phone(&[Meeting { transcribed_ms: Some(0), ..heard("recorded-1") }]);
        fs::write(phone.path("recorded-1.notitia"), "{\"version\":").unwrap();
        assert!(!phone.heard("recorded-1", &["Je prépare la démo."], 0, Some(30_000)));
        assert_eq!(phone.list()[0].transcribed_ms, Some(0));
        assert_eq!(fs::read_to_string(phone.path("recorded-1.notitia")).unwrap(), "{\"version\":");
    }

    #[test]
    fn a_recording_cut_by_a_crash_lasts_as_long_as_its_audio_and_is_heard_on() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.begin("recorded-9");
        let mut writer = phone.library.record(&id("recorded-9"), 1).unwrap();
        chord(130).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        // Killed: the last 30 frames were only in memory.
        drop(writer);
        let phone = phone.relaunched();
        let kept = phone.file("recorded-9").unwrap();
        let expected = (2_000, Some(0), MinutesState::Writing);
        assert_eq!((kept.duration_ms, kept.transcribed_ms, kept.minutes.clone()), expected);
        assert_eq!(phone.list()[0], kept);
        assert_eq!(phone.list()[1], meeting("recorded-1"));
    }

    #[test]
    fn a_launch_opens_only_the_audio_whose_size_tells_another_length() {
        let phone = phone(&[]);
        for (meeting, says_ms) in [("recorded-1", 1_500), ("recorded-2", 900)] {
            phone.begin(meeting);
            phone.lasted(meeting, says_ms);
            // 1.4 s: two seconds, the last one short.
            let mut writer = phone.library.record(&id(meeting), 1).unwrap();
            chord(70).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
            writer.finish().unwrap();
        }
        let phone = phone.relaunched();
        let lasted = |meeting: &str| phone.file(meeting).unwrap().duration_ms;
        // Two seconds as its length says: not opened, though a reader would tell 1.4 s. One second says another.
        assert_eq!((lasted("recorded-1"), lasted("recorded-2")), (1_500, 1_400));
    }

    #[test]
    fn the_seconds_of_an_audio_are_told_by_its_size_alone() {
        let phone = phone(&[]);
        let seconds = |meeting: &str| {
            audio::seconds(Store::open(phone.folder.path().into(), None).unwrap().audio(&id(meeting), false).unwrap())
        };
        for (meeting, frames, held) in
            [("recorded-1", 0, 0), ("recorded-2", 50, 1), ("recorded-3", 51, 2), ("recorded-4", 130, 3)]
        {
            phone.begin(meeting);
            let mut writer = phone.library.record(&id(meeting), 1).unwrap();
            chord(frames).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
            writer.finish().unwrap();
            assert_eq!(seconds(meeting), Ok(held), "{meeting}");
        }
        // A header no version made, or no header at all.
        let path = phone.path("recorded-4.audio");
        let sealed = fs::read(&path).unwrap();
        fs::write(&path, [b"XTTAUDIO".as_slice(), &sealed[8..]].concat()).unwrap();
        assert_eq!(seconds("recorded-4"), Err(AudioError::Unsupported));
        fs::write(&path, &sealed[..20]).unwrap();
        assert_eq!(seconds("recorded-4"), Err(AudioError::Absent));
    }

    #[test]
    fn the_audio_of_a_meeting_is_written_beside_it_and_heard_again() {
        let phone = phone(&[meeting("recorded-1")]);
        let mut writer = phone.library.record(&id("recorded-1"), 1).unwrap();
        chord(70).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        writer.finish().unwrap();
        assert!(phone.path("recorded-1.audio").exists());
        let mut reader = phone.library.audio(&id("recorded-1")).unwrap();
        assert_eq!((reader.duration_ms(), reader.read(0, 1_400).unwrap()[0].len()), (1_400, 22_400));
        // The next launch keeps it, and deleting the meeting does not.
        let phone = launch(phone.folder);
        assert_eq!(phone.library.audio(&id("recorded-1")).unwrap().duration_ms(), 1_400);
        phone.delete(&["recorded-1"]);
        assert!(!phone.path("recorded-1.audio").exists());
        assert_eq!(phone.library.audio(&id("recorded-1")).err(), Some(AudioError::Absent));
    }

    #[test]
    fn only_a_meeting_that_has_a_file_gets_an_audio() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.library.apply(Change::Lend { meetings: vec![meeting("sample-1")] });
        phone.delete(&["recorded-1"]);
        for without in ["sample-1", "recorded-1", "recorded-9"] {
            assert_eq!(phone.library.record(&id(without), 1).err(), Some(AudioError::Absent));
            assert_eq!(phone.library.audio(&id(without)).err(), Some(AudioError::Absent));
            assert!(!phone.path(&format!("{without}.audio")).exists());
        }
    }

    #[test]
    fn without_the_key_of_the_app_no_audio_is_written_nor_read() {
        let phone = phone(&[meeting("recorded-1")]);
        phone.library.record(&id("recorded-1"), 1).unwrap().finish().unwrap();
        let sealed = fs::read(phone.path("recorded-1.audio")).unwrap();
        let app = Arc::new(App::default());
        let keyless = Library::open_at(phone.folder.path().into(), None, app, Box::new(|| NOW)).unwrap();
        assert_eq!(keyless.record(&id("recorded-1"), 1).err(), Some(AudioError::Locked));
        assert_eq!(keyless.audio(&id("recorded-1")).err(), Some(AudioError::Locked));
        assert_eq!(fs::read(phone.path("recorded-1.audio")).unwrap(), sealed);
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
        phone.begin("recorded-9");
        assert!(phone.heard("recorded-9", &["Bonjour à tous."], 0, Some(30_000)));
        assert_eq!(phone.unsaved(), [id("recorded-9")]);
        assert_eq!(phone.library.meeting(&id("recorded-9")).unwrap().transcript, [line(0, "Bonjour à tous.")]);
        fs::remove_dir(phone.path("recorded-9.notitia.tmp")).unwrap();
        phone.rename("recorded-9", "Point produit");
        assert_eq!(phone.unsaved(), []);
        assert_eq!(phone.file("recorded-9"), phone.library.meeting(&id("recorded-9")).ok());
        assert_eq!(phone.file("recorded-9").unwrap().transcript.len(), 1);
    }

    // Begun 30 days before the launch to the minute: the audio of `aged` has been kept as long as the rule says.
    const AGED: UtcDateTime = utc_datetime!(2026-10-12 8:00);

    fn aged(name: &str) -> Meeting {
        Meeting { started_at: AGED, ..heard(name) }
    }

    fn with_audio(phone: &Phone, meeting: &str) {
        let mut writer = phone.library.record(&id(meeting), 1).unwrap();
        chord(70).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        writer.finish().unwrap();
    }

    fn has_audio(phone: &Phone, meeting: &str) -> bool {
        phone.path(&format!("{meeting}.audio")).exists()
    }

    fn tidy(phone: &Phone, spared: Option<&str>) -> bool {
        phone.library.erase_audio(spared.map(id).as_ref(), || {})
    }

    #[test]
    fn the_audio_kept_30_days_is_erased_and_the_rest_of_the_meeting_stays() {
        let titled = Meeting { title: Some("Point produit".into()), ..aged("recorded-1") };
        let younger = Meeting { started_at: AGED + time::Duration::minutes(1), ..heard("recorded-2") };
        let phone = phone(&[titled.clone(), younger, aged("recorded-3")]);
        ["recorded-1", "recorded-2"].iter().for_each(|meeting| with_audio(&phone, meeting));
        let told = phone.told();
        assert!(tidy(&phone, None));
        assert!(!has_audio(&phone, "recorded-1") && has_audio(&phone, "recorded-2"));
        let erased = Meeting { audio_erased_at: Some(NOW), modified_at: NOW, device: phone.device(), ..titled };
        assert_eq!(phone.file("recorded-1"), Some(erased.clone()));
        let shown = phone.list().into_iter().find(|meeting| meeting.id == erased.id);
        assert_eq!(shown, Some(Meeting { transcript: Vec::new(), ..erased }));
        // The meeting begun a minute later keeps its audio; one that never had any is not said to have lost it.
        assert_eq!(phone.file("recorded-2").unwrap().audio_erased_at, None);
        assert_eq!(phone.file("recorded-3"), Some(aged("recorded-3")));
        assert_eq!(phone.told(), told + 1);
        // Done once: the next tidy-up has nothing left to erase.
        assert!(!tidy(&phone, None));
        assert_eq!(phone.told(), told + 1);
        assert_eq!(phone.relaunched().file("recorded-1").unwrap().audio_erased_at, Some(NOW));
    }

    #[test]
    fn neither_the_meeting_being_recorded_nor_a_sample_loses_its_audio() {
        let phone = phone(&[aged("recorded-1")]);
        with_audio(&phone, "recorded-1");
        phone.library.apply(Change::Lend { meetings: vec![aged("sample-1")] });
        assert!(!tidy(&phone, Some("recorded-1")));
        assert!(has_audio(&phone, "recorded-1"));
        assert_eq!(phone.library.meeting(&id("sample-1")).unwrap().audio_erased_at, None);
    }

    #[test]
    fn a_meeting_still_heard_closes_its_transcript_on_what_was_heard() {
        let heard_on = |name: &str, lines: Vec<TranscriptLine>| Meeting {
            transcribed_ms: Some(30_000),
            minutes: MinutesState::Writing,
            transcript: lines,
            ..aged(name)
        };
        let silent = TranscriptLine { at_ms: 0, text: None, speaker: None, extra: Extra::new() };
        // Its lines only in its file, as a launch finds them.
        let phone = phone(&[
            heard_on("recorded-1", vec![line(0, "Bonjour à tous.")]),
            heard_on("recorded-2", vec![silent]),
            Meeting { minutes: aged("recorded-3").minutes, ..heard_on("recorded-3", vec![]) },
        ]);
        ["recorded-1", "recorded-2", "recorded-3"].iter().for_each(|meeting| with_audio(&phone, meeting));
        assert!(tidy(&phone, None));
        let closed = |meeting: &str| {
            let file = phone.file(meeting).unwrap();
            (file.transcribed_ms, file.minutes, file.transcript.len())
        };
        assert_eq!(closed("recorded-1"), (None, MinutesState::Writing, 1));
        let nothing_said = Minutes { summary: String::new(), decisions: vec![], actions: vec![], extra: Extra::new() };
        assert_eq!(closed("recorded-2"), (None, MinutesState::Written { minutes: nothing_said }, 1));
        // Minutes already written stay as they were.
        assert_eq!(closed("recorded-3"), (None, aged("recorded-3").minutes, 0));
        // Nothing is left to hear, so nothing to hear again either.
        assert!(!phone.library.change(Change::Rehear { meeting: id("recorded-1") }));
        assert!(!phone.heard("recorded-1", &["Plus tard."], 30_000, None));
    }

    #[test]
    fn an_audio_marked_erased_is_neither_read_nor_made_again() {
        let phone = phone(&[Meeting { minutes: MinutesState::Writing, ..aged("recorded-1") }]);
        with_audio(&phone, "recorded-1");
        let sealed = fs::read(phone.path("recorded-1.audio")).unwrap();
        // Killed between the mark and the deletion.
        assert!(phone.library.change(Change::Erased { meeting: id("recorded-1") }));
        assert_eq!(phone.library.audio(&id("recorded-1")).err(), Some(AudioError::Absent));
        assert_eq!(phone.library.record(&id("recorded-1"), 1).err(), Some(AudioError::Absent));
        assert_eq!(fs::read(phone.path("recorded-1.audio")).unwrap(), sealed);
        // The launch does not open it to tell its length.
        phone.lasted("recorded-1", 9_000);
        let phone = phone.relaunched();
        assert_eq!(phone.file("recorded-1").unwrap().duration_ms, 9_000);
        let told = phone.told();
        assert!(!tidy(&phone, None), "marked already");
        assert!(!has_audio(&phone, "recorded-1"));
        assert_eq!(phone.told(), told);
    }

    #[test]
    fn an_audio_is_let_go_after_its_meeting_is_marked_and_before_it_is_deleted() {
        let phone = phone(&[aged("recorded-1")]);
        with_audio(&phone, "recorded-1");
        let mut let_go = Vec::new();
        phone.library.erase_audio(None, || {
            let marked = phone.library.audio(&id("recorded-1")).err() == Some(AudioError::Absent);
            let_go.push((marked, has_audio(&phone, "recorded-1")));
        });
        assert_eq!(let_go, [(true, true)]);
        assert!(!has_audio(&phone, "recorded-1"));
    }

    #[test]
    fn an_audio_that_cannot_be_deleted_is_tried_again_at_the_next_tidy_up() {
        let phone = phone(&[aged("recorded-1")]);
        // In its place, what no system deletes as a file, whoever runs the tests.
        fs::create_dir(phone.path("recorded-1.audio")).unwrap();
        assert!(tidy(&phone, None));
        assert!(phone.path("recorded-1.audio").is_dir());
        fs::remove_dir(phone.path("recorded-1.audio")).unwrap();
        fs::write(phone.path("recorded-1.audio"), b"sealed").unwrap();
        assert!(!tidy(&phone, None));
        assert!(!has_audio(&phone, "recorded-1"));
    }

    // The mark could not be written: the next launch would find a meeting without its audio nor the day it went.
    #[test]
    fn without_the_key_no_audio_is_erased() {
        // Written in clear before the seal, so listed without the key, its audio beside it.
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("recorded-1.notitia"), crate::format::encode(&aged("recorded-1")).unwrap())
            .unwrap();
        fs::write(folder.path().join("recorded-1.audio"), b"sealed").unwrap();
        let app = Arc::new(App::default());
        let keyless = Library::open_at(folder.path().into(), None, app.clone(), Box::new(|| NOW)).unwrap();
        assert_eq!(app.list.lock().unwrap().len(), 1);
        assert!(!keyless.erase_audio(None, || {}));
        assert!(folder.path().join("recorded-1.audio").exists());
        assert!(app.unsaved.lock().unwrap().is_empty());
    }

    #[test]
    fn the_first_and_the_last_years_are_tidied_up_without_a_panic() {
        let first = Meeting { started_at: utc_datetime!(0000-01-01 0:00), ..heard("recorded-1") };
        let last = Meeting { started_at: utc_datetime!(9999-12-31 23:59), ..heard("recorded-2") };
        let phone = phone(&[first, last]);
        ["recorded-1", "recorded-2"].iter().for_each(|meeting| with_audio(&phone, meeting));
        assert!(tidy(&phone, None));
        assert!(!has_audio(&phone, "recorded-1") && has_audio(&phone, "recorded-2"));
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

    // Kotlin's version 1 and the core's version 2, both in clear: every file a phone held before the seal.
    #[test]
    fn a_meeting_written_in_clear_is_sealed_at_launch_as_version_2_and_loses_nothing() {
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("recorded-1.notitia"), V1_BY_KOTLIN).unwrap();
        let v2 = V2_BY_THE_CORE.replace("recorded-1", "recorded-2");
        fs::write(folder.path().join("recorded-2.notitia"), &v2).unwrap();
        let read = |text: &str| crate::format::decode(text.as_bytes()).unwrap();
        let (first, second) = (read(V1_BY_KOTLIN), read(&v2));
        let phone = launch(folder);
        assert_eq!(phone.unsaved(), []);
        for (name, before) in [("recorded-1", &first), ("recorded-2", &second)] {
            let file = fs::read(phone.path(&format!("{name}.notitia"))).unwrap();
            assert!(file.starts_with(b"NTTMEET1") && !file.windows(15).any(|window| window == b"Revue de sprint"));
            assert_eq!(phone.file(name).as_ref(), Some(before));
        }
        let phone = phone.relaunched();
        assert_eq!(phone.library.meeting(&id("recorded-1")), Ok(first));
        phone.edit("recorded-1", Edit::SetDone { action: ActionId::new("1").unwrap(), done: true });
        let written = phone.file("recorded-1").unwrap();
        assert_eq!((done(&written), written.transcript.len()), (vec![true, true], 2));
    }

    #[test]
    fn with_the_key_no_word_of_a_meeting_reaches_the_disk_in_clear() {
        let phone = phone(&[heard("recorded-1")]);
        phone.rename("recorded-1", "Revue confidentielle");
        phone.begin("recorded-9");
        assert!(phone.heard("recorded-9", &["Le budget secret est validé."], 0, None));
        let phone = phone.relaunched();
        assert_eq!(phone.list().len(), 2);
        for entry in fs::read_dir(phone.folder.path()).unwrap() {
            let file = fs::read(entry.unwrap().path()).unwrap();
            for words in ["confidentielle", "Bonjour", "budget secret"] {
                assert!(!file.windows(words.len()).any(|window| window == words.as_bytes()), "{words}");
            }
        }
    }

    #[test]
    fn without_the_key_meetings_in_clear_are_shown_and_nothing_is_written() {
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("recorded-1.notitia"), V1_BY_KOTLIN).unwrap();
        let sealed = Store::open(folder.path().into(), Some(key(1))).unwrap();
        sealed.write(&meeting("recorded-2")).unwrap();
        let app = Arc::new(App { folder: folder.path().into(), ..App::default() });
        let keyless = Library::open_at(folder.path().into(), None, app.clone(), Box::new(|| NOW)).unwrap();
        let listed: Vec<MeetingId> = app.list.lock().unwrap().iter().map(|meeting| meeting.id.clone()).collect();
        assert_eq!(listed, [id("recorded-1")]);
        let renamed = Change::Edit { meeting: id("recorded-1"), edit: Edit::Rename { title: "Point produit".into() } };
        keyless.apply(renamed);
        assert_eq!(*app.unsaved.lock().unwrap(), [id("recorded-1")]);
        assert_eq!(keyless.meeting(&id("recorded-1")).unwrap().title.as_deref(), Some("Point produit"));
        assert_eq!(fs::read_to_string(folder.path().join("recorded-1.notitia")).unwrap(), V1_BY_KOTLIN);
        assert!(!folder.path().join("index").exists());
        // Deleting writes no word: done without the key.
        keyless.apply(Change::Delete { meetings: vec![id("recorded-1")] });
        assert!(!folder.path().join("recorded-1.notitia").exists());
        assert_eq!(*app.unsaved.lock().unwrap(), []);
        assert!(folder.path().join("recorded-2.notitia").exists());
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
        let store = Store::open(folder.path().into(), Some(key(1))).unwrap();
        [meeting("recorded-1"), meeting("recorded-2")].iter().for_each(|meeting| store.write(meeting).unwrap());
        let library =
            Library::open_at(folder.path().into(), Some(key(1)), Arc::new(Failing), Box::new(|| NOW)).unwrap();
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
                    // Heard ten minutes in.
                    Meeting { transcribed_ms: Some(600_000), ..with("recorded-2", MinutesState::Writing) },
                    // Its audio erased past the days it was kept.
                    Meeting {
                        audio_erased_at: Some(utc_datetime!(2026-12-10 8:00)),
                        ..with("recorded-3", MinutesState::Failed)
                    },
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
