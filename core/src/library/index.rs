use std::collections::BTreeMap;
use std::fs::Metadata;
use std::time::UNIX_EPOCH;

use serde::{Deserialize, Serialize};
use time::UtcDateTime;

use crate::format;
use crate::meeting::{Meeting, MeetingId};

// Raised when the index changes shape. No other app reads it: it is not part of the format.
const VERSION: u32 = 1;

// The coarsest step of a file system clock, FAT's two seconds: two writes within it may bear the same date.
const CLOCK_STEP: u64 = 2_000_000_000;

// What a file tells without being read, its date in nanoseconds since 1970.
#[derive(Clone, Copy, PartialEq, Serialize, Deserialize)]
pub(super) struct Stat {
    size: u64,
    modified: u64,
}

impl Stat {
    // None when the system gives no date: the file is then read at each launch.
    pub fn of(file: &Metadata) -> Option<Self> {
        let modified = file.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_nanos();
        Some(Self { size: file.len(), modified: u64::try_from(modified).ok()? })
    }
}

#[derive(Serialize, Deserialize)]
struct Kept {
    file: Stat,
    // Without its lines.
    meeting: Meeting,
}

// What the files of the meetings said when they were last read: a cache, which the files overrule. It holds
// titles and minutes, so it is written like a meeting and has no Debug either (product rule 1).
#[derive(Default)]
pub(super) struct Index {
    // When the folder began to be read.
    read_at: u64,
    // Under the identifier of the meeting, which names its file: no entry can speak of another file.
    meetings: BTreeMap<MeetingId, Kept>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Written {
    version: u32,
    // Of the meetings: an index kept under another format is thrown away, with nobody having to think of it.
    format: u32,
    read_at: u64,
    meetings: Vec<Kept>,
}

impl Index {
    // A clock set before 1970 gives an index that trusts nothing.
    pub fn read_at(now: UtcDateTime) -> Self {
        Self { read_at: u64::try_from(now.unix_timestamp_nanos()).unwrap_or(0), ..Self::default() }
    }

    // None for anything but an index this very app wrote: it is then built again from the files.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let written: Written = serde_json::from_slice(bytes).ok()?;
        let ours = written.version == VERSION && written.format == format::VERSION;
        let meetings = written.meetings.into_iter().map(|kept| (kept.meeting.id.clone(), kept));
        ours.then(|| Self { read_at: written.read_at, meetings: meetings.collect() })
    }

    pub fn encode(self) -> Option<Vec<u8>> {
        let meetings = self.meetings.into_values().collect();
        serde_json::to_vec(&Written { version: VERSION, format: format::VERSION, read_at: self.read_at, meetings }).ok()
    }

    // The meeting as its file held it, when the file has the size and the date it had then. Equal, never older
    // than: a clock set back cannot fool it. A file as recent as the index is not trusted, as in Git: a later
    // write within the same step of the clock would leave it the same date.
    pub fn trusted(&self, id: &MeetingId, file: Stat) -> Option<&Meeting> {
        let kept = self.meetings.get(id)?;
        let settled = file.modified.saturating_add(CLOCK_STEP) < self.read_at;
        (kept.file == file && settled).then_some(&kept.meeting)
    }

    pub fn keep(&mut self, file: Stat, meeting: Meeting) {
        self.meetings.insert(meeting.id.clone(), Kept { file, meeting });
    }

    pub fn len(&self) -> usize {
        self.meetings.len()
    }
}

#[cfg(test)]
mod tests {
    use time::macros::utc_datetime;

    use super::*;
    use crate::meeting::testing::meeting;
    use crate::testing::each_damaged;

    #[test]
    fn a_damaged_index_is_thrown_away_or_written_back() {
        let mut index = Index::read_at(utc_datetime!(2026-11-11 8:00));
        for (named, size) in [("recorded-1", 1_200), ("sample-1", 900)] {
            index.keep(Stat { size, modified: 1_794_300_000_000_000_000 }, meeting(named));
        }
        let good = index.encode().unwrap();
        each_damaged(&[&good], 1_000, |file| {
            if let Some(index) = Index::decode(file) {
                let kept = index.len();
                let written = index.encode().expect("an index read is written back");
                assert_eq!(Index::decode(&written).map(|index| index.len()), Some(kept));
            }
        });
    }
}
