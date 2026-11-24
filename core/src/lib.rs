// Memory is safe because the compiler checks it, with no exception: the core holds no unsafe code. The bridge of the
// phones, which UniFFI writes, is a crate of its own.
#![forbid(unsafe_code)]

pub mod audio;
pub mod engine;
pub mod format;
pub mod library;
pub mod meeting;
pub mod minutes;
pub mod pipeline;
pub mod recorder;
pub mod secret;
pub mod session;
pub mod speech;
#[cfg(test)]
mod testing;

// What an app calls: the phones through the bridge of ffi/, the computer directly. The modules stay open for the
// measures of examples/, never for an app.
pub use audio::AudioError;
pub use engine::lent::{Lender, Offer};
pub use library::LibraryError;
pub use meeting::MeetingId;
pub use secret::Key;
pub use session::{Event, Listener, Request, Session};
