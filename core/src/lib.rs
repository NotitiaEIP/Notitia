mod host;
pub mod meeting;
mod meeting_file;

pub use host::{HostProbe, ask_host};
pub use meeting_file::{CoreError, MeetingSummary, read_meeting_summary};

uniffi::setup_scaffolding!();
