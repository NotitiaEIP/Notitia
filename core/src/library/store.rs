use std::fs::{self, DirBuilder, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use uuid::Uuid;

use crate::format;
use crate::meeting::{Meeting, MeetingId};

// The names the Kotlin app gave: the core takes over the folder of a phone as it stands.
const EXTENSION: &str = ".notitia";
const DRAFT: &str = ".tmp";
const DEVICE: &str = "device";

// The one place that touches the disk, one file per meeting: a damaged file loses one meeting and not all of them.
// Every byte goes through read_file and write_file: US-81 encrypts there.
pub(super) struct Store {
    directory: PathBuf,
}

impl Store {
    pub fn open(directory: PathBuf) -> io::Result<Self> {
        let mut folder = DirBuilder::new();
        folder.recursive(true);
        // A computer has no sandbox around the app as a phone has: the meetings are for their owner alone.
        #[cfg(unix)]
        std::os::unix::fs::DirBuilderExt::mode(&mut folder, 0o700);
        folder.create(&directory)?;
        Ok(Self { directory })
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

    // Every meeting without its lines, so the transcripts are never all in memory at once. A file this app cannot
    // read stays on disk: a newer app may read it after an update. A draft goes: it is the words of a meeting the
    // user may have deleted since, and nothing writes while the folder is read.
    pub fn load(&self) -> io::Result<Vec<Meeting>> {
        let mut meetings = Vec::new();
        for entry in fs::read_dir(&self.directory)? {
            let name = entry?.file_name();
            let Some(name) = name.to_str() else { continue };
            if name.ends_with(DRAFT) {
                let _ = fs::remove_file(self.directory.join(name));
            } else if let Some(id) = name.strip_suffix(EXTENSION)
                && let Ok(mut meeting) = self.decode(name)
                // Under another name, deleting the meeting would miss its file and bring it back at each launch.
                && meeting.id.as_str() == id
            {
                meeting.transcript = Vec::new();
                meetings.push(meeting);
            }
        }
        Ok(meetings)
    }

    pub fn read(&self, id: &MeetingId) -> io::Result<Meeting> {
        self.decode(&file_of(id))
    }

    pub fn write(&self, meeting: &Meeting) -> io::Result<()> {
        let bytes = format::encode(meeting).map_err(|_| io::ErrorKind::InvalidData)?;
        self.write_file(&file_of(&meeting.id), &bytes)
    }

    // The draft too: an app killed mid-write leaves one, and it holds the meeting as well. US-81 deletes the audio
    // here.
    pub fn remove(&self, id: &MeetingId) -> io::Result<()> {
        let file = file_of(id);
        for name in [draft_of(&file), file] {
            match fs::remove_file(self.directory.join(name)) {
                Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
                _ => {}
            }
        }
        self.flush_folder();
        Ok(())
    }

    fn decode(&self, name: &str) -> io::Result<Meeting> {
        format::decode(&self.read_file(name)?).map_err(|_| io::ErrorKind::InvalidData.into())
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

fn write_flushed(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let mut file = OpenOptions::new();
    file.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut file, 0o600);
    let mut file = file.open(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

#[cfg(test)]
mod tests {
    use tempfile::TempDir;

    use super::*;
    use crate::meeting::testing::{heard, id, meeting, written};

    fn store() -> (TempDir, Store) {
        let folder = tempfile::tempdir().unwrap();
        let store = Store::open(folder.path().join("meetings")).unwrap();
        (folder, store)
    }

    fn ids(store: &Store) -> Vec<String> {
        let mut ids: Vec<String> = store.load().unwrap().into_iter().map(|meeting| meeting.id.into()).collect();
        ids.sort();
        ids
    }

    #[test]
    fn a_written_meeting_is_read_back_and_listed_without_its_lines() {
        let (_folder, store) = store();
        assert_eq!(store.load().unwrap(), []);
        store.write(&heard("recorded-1")).unwrap();
        assert_eq!(store.read(&id("recorded-1")).unwrap(), heard("recorded-1"));
        assert_eq!(Store::open(store.directory.clone()).unwrap().load().unwrap(), [meeting("recorded-1")]);
    }

    #[test]
    fn writing_again_replaces_the_meeting() {
        let (_folder, store) = store();
        store.write(&meeting("recorded-1")).unwrap();
        let corrected = Meeting { minutes: written(&["Envoyer le compte rendu"]), ..meeting("recorded-1") };
        store.write(&corrected).unwrap();
        assert_eq!(store.load().unwrap(), [corrected]);
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
    }

    #[test]
    fn the_device_keeps_the_same_identifier() {
        let (_folder, store) = store();
        let device = store.device().unwrap();
        assert_eq!(Store::open(store.directory.clone()).unwrap().device().unwrap(), device);
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
        let mode = |path: PathBuf| fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(store.directory.clone()), 0o700);
        assert_eq!(mode(store.directory.join("recorded-1.notitia")), 0o600);
    }
}
