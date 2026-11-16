use std::fmt;

use serde::{Deserialize, Serialize};
use uuid::Uuid;

const LONGEST: usize = 64;

// Says nothing of the text refused: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq)]
pub struct InvalidId;

impl fmt::Display for InvalidId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("the identifier cannot name a file")
    }
}

impl std::error::Error for InvalidId {}

// An identifier names a file, and a file may come from another device: nothing in it can lead out of the folder.
fn checked(text: String) -> Result<String, InvalidId> {
    let allowed = |c: char| c.is_ascii_alphanumeric() || c == '-' || c == '_';
    if text.is_empty() || text.len() > LONGEST || !text.chars().all(allowed) { Err(InvalidId) } else { Ok(text) }
}

// Two types rather than one: the compiler then refuses an action's identifier where a meeting's is expected.
macro_rules! identifier {
    ($name:ident) => {
        #[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
        #[serde(try_from = "String", into = "String")]
        pub struct $name(String);

        impl $name {
            pub fn new(text: impl Into<String>) -> Result<Self, InvalidId> {
                checked(text.into()).map(Self)
            }

            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl TryFrom<String> for $name {
            type Error = InvalidId;

            fn try_from(text: String) -> Result<Self, InvalidId> {
                Self::new(text)
            }
        }

        impl From<$name> for String {
            fn from(id: $name) -> String {
                id.0
            }
        }
    };
}

identifier!(MeetingId);
identifier!(ActionId);

impl ActionId {
    pub fn random() -> Self {
        Self(Uuid::new_v4().to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_identifiers_of_the_apps_are_accepted() {
        for id in ["recorded-1794303000000", "sample-1", "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", "0", "a_b"] {
            assert_eq!(MeetingId::new(id).unwrap().as_str(), id);
        }
    }

    #[test]
    fn an_identifier_that_cannot_name_a_file_is_refused() {
        for id in ["", "../recorded-1", "a/b", "a\\b", "a.b", "a b", "réunion", "a\0"] {
            assert_eq!(MeetingId::new(id), Err(InvalidId));
        }
        assert!(MeetingId::new("a".repeat(LONGEST)).is_ok());
        assert_eq!(MeetingId::new("a".repeat(LONGEST + 1)), Err(InvalidId));
    }

    #[test]
    fn a_drawn_identifier_is_valid_and_its_own() {
        let (first, second) = (ActionId::random(), ActionId::random());
        assert_ne!(first, second);
        assert_eq!(ActionId::new(first.as_str()), Ok(first));
    }

    #[test]
    fn an_identifier_read_from_a_file_is_checked() {
        assert_eq!(serde_json::from_str::<ActionId>("\"0\"").unwrap(), ActionId::new("0").unwrap());
        assert!(serde_json::from_str::<ActionId>("\"../0\"").is_err());
    }
}
