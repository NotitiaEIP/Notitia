// What the tests of several modules share, and the pins of the witnesses of the formats. Never built into an app.

use std::fs;
use std::path::Path;

use crate::secret::testing::fingerprint;

// Every witness of a format, as an app first wrote it, and its SHA-256 (docs/adr/0009-garde-fous.md). A format is for
// ever: a witness that changes is a file every device already holds, which the core no longer reads the same way. A
// new version comes with a witness of its own and a line here; no line is ever changed.
const WITNESSES: [(&str, &str); 5] = [
    ("audio-v1.audio", "99b21bbd32f4febdf4aaad03b79a8f5390a973fa1e9b145c9f18800eefef95d4"),
    ("meeting-v1-recording.notitia", "e55012494920ac5e094fb08f047a6d3808f631760ccc44a092cc8ced5675d3ed"),
    ("meeting-v1-reminder.notitia", "5995e3cd73081b3f0ad060cdc5ec49a822b48a6716d563b3b004c559ebea878c"),
    ("meeting-v1.notitia", "8d96f57bfa1c76dc6b04822888d5726c29810ea2515b2a65f1075fcc38fadcc3"),
    ("meeting-v2.notitia", "122f1bc7ccbfe30552c1aed01e96eb7083bb5fa625a7d3633027822a81c8f15c"),
];

#[test]
fn the_witnesses_of_the_formats_are_the_files_first_written() {
    let folder = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    // The examples of the bridge live in a folder of their own: they change with both sides of it. A name with a dot
    // first is a system's own, as macOS leaves one in every folder it shows.
    let mut found: Vec<String> = fs::read_dir(&folder)
        .unwrap()
        .map(|entry| entry.unwrap())
        .filter(|entry| entry.file_type().unwrap().is_file())
        .map(|entry| entry.file_name().into_string().unwrap())
        .filter(|name| !name.starts_with('.'))
        .collect();
    found.sort();
    assert_eq!(found, WITNESSES.map(|(name, _)| name), "a witness is pinned here as soon as it is written");
    for (name, pinned) in WITNESSES {
        assert_eq!(
            fingerprint(&fs::read(folder.join(name)).unwrap()),
            pinned,
            "{name}: add a version, never change one"
        );
    }
}
