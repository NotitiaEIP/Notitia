use std::collections::HashSet;
use std::fs::{self, DirBuilder, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use time::UtcDateTime;
use uuid::Uuid;

use super::index::{Index, Stat};
use super::sealed::{self, Kind};
use crate::format;
use crate::meeting::{Meeting, MeetingId};
use crate::secret::Key;

// The names the Kotlin app gave: the core takes over the folder of a phone as it stands.
const EXTENSION: &str = ".notitia";
const DRAFT: &str = ".tmp";
const AUDIO: &str = ".audio";
const DEVICE: &str = "device";
const INDEX: &str = "index";

// The one place that touches the disk, one file per meeting: a damaged file loses one meeting and not all of them.
// A meeting and the index are sealed here (src/library/sealed.rs), its audio by src/audio, which is handed the file.
pub(super) struct Store {
    directory: PathBuf,
    // The key of the app. Without one nothing that holds a meeting is written: none ever reaches the disk in clear.
    key: Option<Key>,
}

// What the folder holds, as a launch finds it.
pub(super) struct Loaded {
    // Without their lines.
    pub meetings: Vec<Meeting>,
    // The index to keep, when the folder no longer says what the last one did.
    pub index: Option<Index>,
    // Read from a file written in clear, before the files were sealed: to be written again, sealed.
    pub clear: Vec<MeetingId>,
}

impl Store {
    pub fn open(directory: PathBuf, key: Option<Key>) -> io::Result<Self> {
        let mut folder = DirBuilder::new();
        folder.recursive(true);
        // A computer has no sandbox around the app as a phone has: the meetings are for their owner alone.
        #[cfg(unix)]
        std::os::unix::fs::DirBuilderExt::mode(&mut folder, 0o700);
        folder.create(&directory)?;
        Ok(Self { directory, key })
    }

    pub fn key(&self) -> Option<&Key> {
        self.key.as_ref()
    }

    // Drawn once per install, never derived from the device, so it says nothing about its owner. Drawn again when
    // the file is blank, as a power cut left it under the Kotlin app, which did not flush its writes.
    pub fn device(&self) -> io::Result<String> {
        let kept = match self.read_file(DEVICE) {
            Ok(bytes) => String::from_utf8(bytes).ok().filter(|device| !device.trim().is_empty()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => None,
            Err(error) => return Err(error),
        };
        match kept {
            Some(device) => Ok(device),
            None => {
                let device = Uuid::new_v4().to_string();
                self.write_file(DEVICE, device.as_bytes())?;
                Ok(device)
            }
        }
    }

    // Every meeting without its lines, so the transcripts are never all in memory at once. A file this app cannot read
    // stays on disk: a newer app, or the key it was sealed with, may read it later. A draft goes: it is the words of a
    // meeting the user may have deleted since, and nothing writes while the folder is read.
    pub fn load(&self, now: UtcDateTime) -> io::Result<Loaded> {
        // Only an index sealed by this key: one in clear was written before the seal, and trusts files in clear.
        let file = self.read_file(INDEX).ok();
        let known = file.as_ref().and_then(|file| self.unsealed(Kind::Index, file).ok());
        let known = known.and_then(|bytes| Index::decode(&bytes));
        // Replaced at once, even by an empty one: the index in clear of before the seal holds titles and minutes.
        let unusable = file.is_some() && known.is_none();
        let known = known.unwrap_or_default();
        let mut index = Index::read_at(now);
        let (mut meetings, mut clear) = (Vec::new(), Vec::new());
        let mut trusted = 0;
        let (mut audios, mut files) = (Vec::new(), HashSet::new());
        for entry in fs::read_dir(&self.directory)? {
            let name = entry?.file_name();
            let Some(name) = name.to_str() else { continue };
            if name.ends_with(DRAFT) {
                let _ = fs::remove_file(self.directory.join(name));
            } else if name.ends_with(AUDIO) {
                audios.push(name.to_owned());
            } else if let Some(named) = name.strip_suffix(EXTENSION) {
                // Read or not: a meeting a newer app wrote keeps its audio.
                files.insert(named.to_owned());
                let Ok(id) = MeetingId::new(named) else { continue };
                // Its size and date before its words: a file that changes in between is kept newer than its date
                // says, and the next launch reads it again. The other way round would hide the change.
                let file = fs::metadata(self.directory.join(name)).ok().as_ref().and_then(Stat::of);
                let (meeting, sealed) = match file.and_then(|file| known.trusted(&id, file)) {
                    Some(kept) => {
                        trusted += 1;
                        (kept.clone(), true)
                    }
                    None => match self.decode(&id) {
                        // Under another name, deleting the meeting would miss its file and bring it back at each
                        // launch.
                        Ok((read, sealed)) if read.id == id => (Meeting { transcript: Vec::new(), ..read }, sealed),
                        _ => continue,
                    },
                };
                // Never in the index while in clear: an index that trusted it would keep it so for ever.
                if !sealed {
                    clear.push(id);
                } else if let Some(file) = file {
                    index.keep(file, meeting.clone());
                }
                meetings.push(meeting);
            }
        }
        // An audio whose meeting is gone: the app was killed between the two files of a deletion.
        for audio in audios.iter().filter(|audio| !audio.strip_suffix(AUDIO).is_some_and(|named| files.contains(named)))
        {
            let _ = fs::remove_file(self.directory.join(audio));
        }
        let unchanged = !unusable && trusted == known.len() && trusted == index.len();
        Ok(Loaded { meetings, index: (!unchanged).then_some(index), clear })
    }

    // A cache: when it cannot be written, or without a key, the next launch reads the files again.
    pub fn keep(&self, index: Index) {
        let sealed =
            self.key.as_ref().zip(index.encode()).and_then(|(key, json)| sealed::seal(key, Kind::Index, &json).ok());
        if let Some(bytes) = sealed {
            let _ = self.write_file(INDEX, &bytes);
        }
    }

    pub fn read(&self, id: &MeetingId) -> io::Result<Meeting> {
        self.decode(id).map(|(meeting, _)| meeting)
    }

    // Sealed, or not at all.
    pub fn write(&self, meeting: &Meeting) -> io::Result<()> {
        let key = self.key.as_ref().ok_or(io::ErrorKind::PermissionDenied)?;
        let json = format::encode(meeting).map_err(|_| io::ErrorKind::InvalidData)?;
        let bytes = sealed::seal(key, Kind::Meeting(&meeting.id), &json).map_err(|_| io::ErrorKind::Other)?;
        self.write_file(&file_of(&meeting.id), &bytes)
    }

    // The audio of a meeting, which only src/audio reads: opened to read, and to write when it is to be `made`, the
    // first time or again.
    pub fn audio(&self, id: &MeetingId, made: bool) -> io::Result<File> {
        let file = owned().read(true).write(made).create(made).open(self.directory.join(audio_of(id)))?;
        if made {
            self.flush_folder();
        }
        Ok(file)
    }

    // The draft too: an app killed mid-write leaves one, and it holds the meeting as well. The audio last: killed
    // before it, the app finds an audio without a meeting at its next launch, never a deleted meeting back.
    pub fn remove(&self, id: &MeetingId) -> io::Result<()> {
        let file = file_of(id);
        for name in [draft_of(&file), file, audio_of(id)] {
            match fs::remove_file(self.directory.join(name)) {
                Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
                _ => {}
            }
        }
        self.flush_folder();
        Ok(())
    }

    // And whether its file was sealed. A file in clear was written before the seal, and is still read: without a key,
    // it is all a launch can show.
    fn decode(&self, id: &MeetingId) -> io::Result<(Meeting, bool)> {
        let file = self.read_file(&file_of(id))?;
        let sealed = !sealed::is_clear(&file);
        let json = if sealed { self.unsealed(Kind::Meeting(id), &file)? } else { file };
        let meeting = format::decode(&json).map_err(|_| io::ErrorKind::InvalidData)?;
        Ok((meeting, sealed))
    }

    fn unsealed(&self, kind: Kind, file: &[u8]) -> io::Result<Vec<u8>> {
        let key = self.key.as_ref().ok_or(io::ErrorKind::PermissionDenied)?;
        sealed::open(key, kind, file).map_err(|_| io::ErrorKind::InvalidData.into())
    }

    fn read_file(&self, name: &str) -> io::Result<Vec<u8>> {
        fs::read(self.directory.join(name))
    }

    // Written aside, flushed, then moved over: a killed app or a power cut leaves the previous version, never half
    // a file. A draft that failed goes at once: it holds the words of a meeting.
    fn write_file(&self, name: &str, bytes: &[u8]) -> io::Result<()> {
        let draft = self.directory.join(draft_of(name));
        let moved = write_flushed(&draft, bytes).and_then(|()| fs::rename(&draft, self.directory.join(name)));
        if moved.is_ok() {
            self.flush_folder();
        } else {
            let _ = fs::remove_file(&draft);
        }
        moved
    }

    // So a power cut does not undo a rename or bring a deleted meeting back. Whether it worked is not asked, as in
    // SQLite: not every system can flush a folder, and the file itself is already safe.
    fn flush_folder(&self) {
        #[cfg(unix)]
        let _ = fs::File::open(&self.directory).and_then(|folder| folder.sync_all());
    }
}

fn file_of(id: &MeetingId) -> String {
    format!("{}{EXTENSION}", id.as_str())
}

fn draft_of(name: &str) -> String {
    format!("{name}{DRAFT}")
}

fn audio_of(id: &MeetingId) -> String {
    format!("{}{AUDIO}", id.as_str())
}

// A new file is for its owner alone, as the folder is. Windows has no such mode: there the folder of the app sits in
// its user's profile, closed to the other users.
fn owned() -> OpenOptions {
    #[cfg_attr(not(unix), expect(unused_mut))]
    let mut file = OpenOptions::new();
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut file, 0o600);
    file
}

fn write_flushed(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let mut file = owned().write(true).create(true).truncate(true).open(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

#[cfg(test)]
mod tests {
    use tempfile::TempDir;
    use time::Duration;
    use time::macros::utc_datetime;

    use super::*;
    use crate::meeting::testing::{heard, id, meeting, written};
    use crate::secret::testing::key;

    const V1_BY_KOTLIN: &str = include_str!("../../tests/fixtures/meeting-v1.notitia");
    const FILE: &str = "recorded-1.notitia";
    // A launch, the one after it, and a write long before both. The files a test does not date bear the day the
    // test runs, later than any of them: never trusted, they are read at each launch as without an index.
    const OPENED: UtcDateTime = utc_datetime!(2026-11-11 8:00);
    const LATER: UtcDateTime = utc_datetime!(2026-11-12 8:00);
    const BEFORE: UtcDateTime = utc_datetime!(2026-11-10 8:00);

    impl Store {
        // As a write at that time would have left the file.
        pub(in crate::library) fn date(&self, id: &MeetingId, at: UtcDateTime) {
            let file = OpenOptions::new().write(true).open(self.directory.join(file_of(id))).unwrap();
            file.set_modified(at.into()).unwrap();
        }
    }

    fn store() -> (TempDir, Store) {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().join("meetings"), Some(key(1))).unwrap();
        (folder, store)
    }

    // What a launch lists, by identifier, its index kept as a launch keeps it.
    fn opened(store: &Store, now: UtcDateTime) -> Vec<Meeting> {
        let Loaded { mut meetings, index, .. } = store.load(now).unwrap();
        index.into_iter().for_each(|index| store.keep(index));
        meetings.sort_by(|a, b| a.id.cmp(&b.id));
        meetings
    }

    fn ids(store: &Store) -> Vec<String> {
        opened(store, OPENED).into_iter().map(|meeting| meeting.id.into()).collect()
    }

    fn titled(title: &str) -> Meeting {
        Meeting { title: Some(title.into()), ..meeting("recorded-1") }
    }

    // The index as the last launch sealed it, opened.
    fn index_json(store: &Store) -> String {
        let file = fs::read(store.directory.join(INDEX)).unwrap();
        String::from_utf8(sealed::open(&key(1), Kind::Index, &file).unwrap()).unwrap()
    }

    fn holds(path: PathBuf, words: &str) -> bool {
        fs::read(path).unwrap().windows(words.len()).any(|window| window == words.as_bytes())
    }

    // A meeting listed at OPENED, then replaced outside the app by one of the same size, its date left as it was.
    fn replaced_unseen(written: UtcDateTime) -> (TempDir, Store) {
        let (folder, store) = store();
        store.write(&titled("Point A")).unwrap();
        store.date(&id("recorded-1"), written);
        assert_eq!(opened(&store, OPENED), [titled("Point A")]);
        store.write(&titled("Point B")).unwrap();
        store.date(&id("recorded-1"), written);
        (folder, store)
    }

    #[test]
    fn a_written_meeting_is_read_back_and_listed_without_its_lines() {
        let (_folder, store) = store();
        assert_eq!(opened(&store, OPENED), []);
        store.write(&heard("recorded-1")).unwrap();
        assert_eq!(store.read(&id("recorded-1")).unwrap(), heard("recorded-1"));
        assert_eq!(
            opened(&Store::open(store.directory.clone(), Some(key(1))).unwrap(), OPENED),
            [meeting("recorded-1")]
        );
    }

    #[test]
    fn writing_again_replaces_the_meeting() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        let corrected = Meeting { minutes: written(&["Envoyer le compte rendu"]), ..meeting("recorded-1") };
        store.write(&corrected).unwrap();
        assert_eq!(opened(&store, OPENED), [corrected]);
    }

    #[test]
    fn a_damaged_file_hides_only_its_own_meeting_and_stays_on_disk() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        let damaged = store.directory.join("recorded-2.notitia");
        fs::write(&damaged, "{\"version\":").unwrap();
        assert_eq!(ids(&store), ["recorded-1"]);
        assert!(damaged.exists());
        assert!(store.read(&id("recorded-2")).is_err());
    }

    #[test]
    fn a_file_named_after_another_meeting_is_not_listed() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        fs::copy(store.directory.join("recorded-1.notitia"), store.directory.join("recorded-2.notitia")).unwrap();
        assert_eq!(ids(&store), ["recorded-1"]);
        // Its seal names the meeting: its lines are not read as those of another either.
        assert!(store.read(&id("recorded-2")).is_err());
    }

    #[test]
    fn a_file_sealed_by_another_key_is_hidden_and_stays_on_disk() {
        let (_folder, store) = store();
        store.write(&heard("recorded-1")).unwrap();
        let sealed = fs::read(store.directory.join(FILE)).unwrap();
        let other = Store::open(store.directory.clone(), Some(key(2))).unwrap();
        assert_eq!(opened(&other, OPENED), []);
        assert!(other.read(&id("recorded-1")).is_err());
        assert_eq!(fs::read(store.directory.join(FILE)).unwrap(), sealed);
        assert_eq!(ids(&store), ["recorded-1"]);
    }

    #[test]
    fn a_meeting_and_the_index_are_sealed_on_the_disk() {
        let (_folder, store) = store();
        store.write(&Meeting { title: Some("Revue confidentielle".into()), ..heard("recorded-1") }).unwrap();
        assert_eq!(opened(&store, OPENED).len(), 1);
        for name in [FILE, INDEX] {
            let file = store.directory.join(name);
            assert!(!holds(file.clone(), "confidentielle") && !holds(file.clone(), "Bonjour"), "{name}");
            assert!(!sealed::is_clear(&fs::read(file).unwrap()));
        }
        assert!(index_json(&store).contains("Revue confidentielle"));
    }

    #[test]
    fn without_a_key_files_in_clear_are_read_and_nothing_is_written() {
        let (_folder, store) = store();
        store.write(&heard("recorded-2")).unwrap();
        fs::write(store.directory.join(FILE), V1_BY_KOTLIN).unwrap();
        let keyless = Store::open(store.directory.clone(), None).unwrap();
        let Loaded { meetings, index, clear } = keyless.load(OPENED).unwrap();
        let listed: Vec<String> = meetings.into_iter().map(|meeting| meeting.id.into()).collect();
        assert_eq!((listed, clear), (vec!["recorded-1".to_owned()], vec![id("recorded-1")]));
        index.into_iter().for_each(|index| keyless.keep(index));
        assert!(!store.directory.join(INDEX).exists());
        assert_eq!(keyless.write(&meeting("recorded-3")).unwrap_err().kind(), io::ErrorKind::PermissionDenied);
        assert!(!store.directory.join("recorded-3.notitia").exists());
        assert!(!store.directory.join("recorded-3.notitia.tmp").exists());
        assert!(keyless.read(&id("recorded-2")).is_err());
        assert_eq!(keyless.read(&id("recorded-1")).unwrap().transcript.len(), 2);
    }

    #[test]
    fn the_device_keeps_the_same_identifier() {
        let (_folder, store) = store();
        let device = store.device().unwrap();
        // Drawn by chance, it says nothing of a meeting: kept without a key.
        assert_eq!(Store::open(store.directory.clone(), None).unwrap().device().unwrap(), device);
        fs::write(store.directory.join(DEVICE), "").unwrap();
        let drawn_again = store.device().unwrap();
        assert!(!drawn_again.is_empty());
        assert_eq!(store.device().unwrap(), drawn_again);
    }

    #[test]
    fn a_removed_meeting_is_not_read_back_and_its_draft_goes_with_it() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        store.write(&meeting("recorded-2")).unwrap();
        let draft = store.directory.join("recorded-1.notitia.tmp");
        fs::write(&draft, "{\"version\":").unwrap();
        store.remove(&id("recorded-1")).unwrap();
        assert!(!draft.exists());
        assert_eq!(ids(&store), ["recorded-2"]);
        store.remove(&id("recorded-1")).unwrap();
    }

    #[test]
    fn the_audio_of_a_meeting_is_made_once_and_goes_with_it() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        assert_eq!(store.audio(&id("recorded-1"), false).unwrap_err().kind(), io::ErrorKind::NotFound);
        store.audio(&id("recorded-1"), true).unwrap().write_all(b"sealed").unwrap();
        // Made again, it is the same file: a recording takes up what it wrote.
        assert_eq!(store.audio(&id("recorded-1"), true).unwrap().metadata().unwrap().len(), 6);
        store.remove(&id("recorded-1")).unwrap();
        assert!(!store.directory.join("recorded-1.audio").exists());
    }

    #[test]
    fn an_audio_without_the_file_of_its_meeting_is_removed_at_launch() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        // A meeting this app cannot read, as a newer one may write it.
        fs::write(store.directory.join("recorded-2.notitia"), "{\"version\":").unwrap();
        for recorded in ["recorded-1", "recorded-2", "recorded-3"] {
            store.audio(&id(recorded), true).unwrap();
        }
        assert_eq!(ids(&store), ["recorded-1"]);
        let kept = |recorded: &str| store.directory.join(format!("{recorded}.audio")).exists();
        assert!(kept("recorded-1") && kept("recorded-2") && !kept("recorded-3"));
    }

    #[test]
    fn a_draft_left_by_a_killed_app_is_deleted_when_the_folder_is_read() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        let draft = store.directory.join("recorded-1.notitia.tmp");
        fs::write(&draft, "{\"version\":").unwrap();
        assert_eq!(ids(&store), ["recorded-1"]);
        assert!(!draft.exists());
    }

    #[test]
    fn a_write_that_fails_leaves_the_previous_version() {
        let (_folder, store) = store();
        store.write(&heard("recorded-1")).unwrap();
        // A folder where the draft goes: the disk refuses the write, whoever runs the tests.
        fs::create_dir(store.directory.join("recorded-1.notitia.tmp")).unwrap();
        assert!(store.write(&meeting("recorded-1")).is_err());
        assert_eq!(store.read(&id("recorded-1")).unwrap(), heard("recorded-1"));
    }

    #[test]
    fn a_write_that_fails_leaves_no_draft() {
        let (_folder, store) = store();
        fs::create_dir(store.directory.join("recorded-1.notitia")).unwrap();
        assert!(store.write(&heard("recorded-1")).is_err());
        assert!(!store.directory.join("recorded-1.notitia.tmp").exists());
    }

    #[cfg(unix)]
    #[test]
    fn the_folder_and_its_files_are_for_their_owner_alone() {
        use std::os::unix::fs::PermissionsExt;

        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        store.audio(&id("recorded-1"), true).unwrap();
        opened(&store, OPENED);
        let mode = |path: PathBuf| fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(store.directory.clone()), 0o700);
        assert_eq!(mode(store.directory.join(FILE)), 0o600);
        assert_eq!(mode(store.directory.join("recorded-1.audio")), 0o600);
        // The index holds titles and minutes: it is written as a meeting is.
        assert_eq!(mode(store.directory.join(INDEX)), 0o600);
        assert!(!store.directory.join("index.tmp").exists());
    }

    #[test]
    fn the_index_keeps_every_meeting_without_its_lines() {
        let (_folder, store) = store();
        store.write(&Meeting { title: Some("Revue de sprint".into()), ..heard("recorded-1") }).unwrap();
        store.write(&heard("recorded-2")).unwrap();
        ["recorded-1", "recorded-2"].iter().for_each(|meeting| store.date(&id(meeting), BEFORE));
        let read_from_the_files = opened(&store, OPENED);
        assert_eq!(read_from_the_files.len(), 2);
        let index = index_json(&store);
        assert!(index.contains("Revue de sprint") && !index.contains("transcript"));
        let Loaded { meetings: mut kept, index: to_write, clear } = store.load(LATER).unwrap();
        kept.sort_by(|a, b| a.id.cmp(&b.id));
        assert_eq!(kept, read_from_the_files);
        assert!(to_write.is_none() && clear.is_empty());
    }

    // As a launch before the seal left it: every file in clear, and the index in clear, titles and minutes in it.
    #[test]
    fn an_index_in_clear_is_sealed_at_the_first_launch_even_with_every_file_in_clear() {
        let (_folder, store) = store();
        fs::write(store.directory.join(FILE), V1_BY_KOTLIN).unwrap();
        let mut before = Index::read_at(BEFORE);
        let file = Stat::of(&fs::metadata(store.directory.join(FILE)).unwrap()).unwrap();
        before.keep(file, store.read(&id("recorded-1")).unwrap());
        fs::write(store.directory.join(INDEX), before.encode().unwrap()).unwrap();
        assert!(holds(store.directory.join(INDEX), "Revue de sprint"));
        assert_eq!(ids(&store), ["recorded-1"]);
        assert!(!holds(store.directory.join(INDEX), "Revue de sprint"));
        assert!(!sealed::is_clear(&fs::read(store.directory.join(INDEX)).unwrap()));
    }

    // The store reads it; the library writes it again, sealed. Until then, each launch reads it from its file.
    #[test]
    fn a_file_in_clear_is_read_but_never_kept_in_the_index() {
        let (_folder, store) = store();
        fs::write(store.directory.join(FILE), V1_BY_KOTLIN).unwrap();
        store.write(&heard("recorded-2")).unwrap();
        ["recorded-1", "recorded-2"].iter().for_each(|meeting| store.date(&id(meeting), BEFORE));
        for launch in [OPENED, LATER] {
            let Loaded { meetings, index, clear } = store.load(launch).unwrap();
            index.into_iter().for_each(|index| store.keep(index));
            assert_eq!((meetings.len(), clear), (2, vec![id("recorded-1")]));
        }
        assert!(!index_json(&store).contains("Revue de sprint"));
        assert_eq!(fs::read_to_string(store.directory.join(FILE)).unwrap(), V1_BY_KOTLIN);
    }

    // The limit of any cache by date, and the proof that the file was not opened.
    #[test]
    fn a_file_with_the_size_and_the_date_it_had_is_not_read_again() {
        let (_folder, store) = replaced_unseen(BEFORE);
        assert_eq!(opened(&store, LATER), [titled("Point A")]);
    }

    #[test]
    fn a_file_as_recent_as_the_index_is_read_again() {
        for written in [OPENED - Duration::seconds(2), OPENED - Duration::seconds(1), OPENED + Duration::hours(1)] {
            let (_folder, store) = replaced_unseen(written);
            assert_eq!(opened(&store, LATER), [titled("Point B")]);
            // Older than the index it is now kept in: trusted from here on.
            assert!(store.load(LATER + Duration::hours(2)).unwrap().index.is_none());
        }
    }

    #[test]
    fn an_index_missing_damaged_in_clear_of_another_version_or_key_is_built_again() {
        fn resealed(key: Key, json: String) -> Option<Vec<u8>> {
            Some(sealed::seal(&key, Kind::Index, json.as_bytes()).unwrap())
        }
        // Given the index as sealed, then opened.
        type Damage = fn(&[u8], String) -> Option<Vec<u8>>;
        let damages: [Damage; 7] = [
            |_, _| None,
            |_, _| Some(Vec::new()),
            |file, _| Some(file[..20].into()),
            // As the app wrote it before the seal: it trusted files in clear.
            |_, json| Some(json.into_bytes()),
            |_, json| resealed(key(1), json.replace("\"version\":1,", "\"version\":2,")),
            |_, json| resealed(key(1), json.replace("\"format\":2,", "\"format\":1,")),
            |_, json| resealed(key(2), json),
        ];
        for damage in damages {
            let (_folder, store) = replaced_unseen(BEFORE);
            let index = store.directory.join(INDEX);
            let kept = fs::read(&index).unwrap();
            let damaged = damage(&kept, index_json(&store));
            assert_ne!(damaged.as_deref(), Some(kept.as_slice()));
            match damaged {
                Some(damaged) => fs::write(&index, damaged).unwrap(),
                None => fs::remove_file(&index).unwrap(),
            }
            assert_eq!(opened(&store, LATER), [titled("Point B")]);
            assert!(store.load(LATER).unwrap().index.is_none());
        }
    }

    #[test]
    fn a_file_changed_added_or_removed_outside_the_app_is_seen_at_the_next_launch() {
        let (_folder, store) = store();
        let dated = |meeting: &Meeting, at: UtcDateTime| {
            store.write(meeting).unwrap();
            store.date(&meeting.id, at);
        };
        ["recorded-1", "recorded-2", "recorded-3"].iter().for_each(|stored| dated(&meeting(stored), BEFORE));
        assert_eq!(opened(&store, OPENED).len(), 3);
        let of_another_size = titled("Point produit");
        let of_another_date = Meeting { device: "phone-2".into(), ..meeting("recorded-2") };
        dated(&of_another_size, BEFORE);
        dated(&of_another_date, BEFORE + Duration::seconds(1));
        fs::remove_file(store.directory.join("recorded-3.notitia")).unwrap();
        dated(&meeting("recorded-4"), BEFORE);
        assert_eq!(opened(&store, LATER), [of_another_size, of_another_date, meeting("recorded-4")]);
        assert!(store.load(LATER).unwrap().index.is_none());
    }

    #[test]
    fn an_index_that_cannot_be_written_is_not_an_error() {
        let (_folder, store) = store();
        fs::create_dir(store.directory.join(INDEX)).unwrap();
        store.write(&meeting("recorded-1")).unwrap();
        store.date(&id("recorded-1"), BEFORE);
        for launch in [OPENED, LATER] {
            assert_eq!(opened(&store, launch), [meeting("recorded-1")]);
        }
        assert!(!store.directory.join("index.tmp").exists());
    }
}
