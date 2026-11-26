use crate::meeting::MeetingId;
use crate::secret::{Key, SALT_BYTES, SecretError, random};

// A meeting and the index of the list as they lie on the disk (docs/adr/0014-reunions-scellees.md): a mark that says
// which file and which seal, a salt, then the JSON sealed whole. The JSON stays the format (ADR 0003), the one a file
// sent to another device will carry: this is only the envelope of the disk.

const MARK_BYTES: usize = 8;
const HEADER_BYTES: usize = MARK_BYTES + SALT_BYTES;

#[derive(Clone, Copy)]
pub(super) enum Kind<'a> {
    Meeting(&'a MeetingId),
    Index,
}

impl Kind<'_> {
    // The digit is the version of the seal, not of the JSON it holds.
    fn mark(self) -> &'static [u8; MARK_BYTES] {
        match self {
            Kind::Meeting(_) => b"NTTMEET1",
            Kind::Index => b"NTTINDX1",
        }
    }

    // Told to the key: what seals a meeting opens no index, and no audio.
    fn purpose(self) -> &'static str {
        match self {
            Kind::Meeting(_) => "notitia meeting 1",
            Kind::Index => "notitia index 1",
        }
    }

    // Sealed with the bytes: the file of a meeting copied under the name of another is refused there.
    fn about(self, header: &[u8]) -> Vec<u8> {
        match self {
            Kind::Meeting(id) => [header, id.as_str().as_bytes()].concat(),
            Kind::Index => header.to_vec(),
        }
    }
}

pub(super) fn seal(key: &Key, kind: Kind, clear: &[u8]) -> Result<Vec<u8>, SecretError> {
    let salt = random::<SALT_BYTES>()?;
    let header = [kind.mark().as_slice(), &salt].concat();
    let sealed = key.derive(kind.purpose(), &salt)?.seal(&kind.about(&header), clear)?;
    Ok([header, sealed].concat())
}

// Forged for anything else: another kind of file, another key, another name, a newer seal, or bytes changed since.
pub(super) fn open(key: &Key, kind: Kind, file: &[u8]) -> Result<Vec<u8>, SecretError> {
    let (header, sealed) = file.split_at_checked(HEADER_BYTES).ok_or(SecretError::Forged)?;
    let (mark, salt) = header.split_at(MARK_BYTES);
    let salt = <&[u8; SALT_BYTES]>::try_from(salt).map_err(|_| SecretError::Forged)?;
    if mark != kind.mark() {
        return Err(SecretError::Forged);
    }
    key.derive(kind.purpose(), salt)?.open(&kind.about(header), sealed)
}

// Written before the files were sealed: the JSON of a meeting opens an object, and no mark begins with a brace.
pub(super) fn is_clear(file: &[u8]) -> bool {
    file.iter().find(|byte| !byte.is_ascii_whitespace()) == Some(&b'{')
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::format;
    use crate::meeting::testing::id;
    use crate::secret::testing::key;
    use crate::testing::each_damaged;

    // The JSON of meeting-v2.notitia, sealed once by key(1) under the name recorded-1 and never written again: the
    // core of any later app opens it as it was sealed.
    const WITNESS: &[u8] = include_bytes!("../../tests/fixtures/meeting-sealed-v1.notitia");
    const VERSION_2: &[u8] = include_bytes!("../../tests/fixtures/meeting-v2.notitia");
    const CLEAR: &[u8] = b"{\"made up\":\"for the tests\"}";

    fn meeting(named: &MeetingId) -> Kind<'_> {
        Kind::Meeting(named)
    }

    #[test]
    fn the_witness_of_version_1_opens_as_it_was_sealed() {
        let opened = open(&key(1), meeting(&id("recorded-1")), WITNESS).unwrap();
        assert_eq!(opened, VERSION_2);
        assert_eq!(format::decode(&opened).unwrap().id, id("recorded-1"));
        assert_eq!(&WITNESS[..MARK_BYTES], b"NTTMEET1");
    }

    #[test]
    fn what_is_sealed_opens_with_its_key_as_its_kind_and_under_its_name() {
        let recorded = id("recorded-1");
        for kind in [meeting(&recorded), Kind::Index] {
            let sealed = seal(&key(1), kind, CLEAR).unwrap();
            assert_eq!(open(&key(1), kind, &sealed).unwrap(), CLEAR);
            assert!(!sealed.windows(CLEAR.len()).any(|window| window == CLEAR));
            assert!(!is_clear(&sealed));
        }
    }

    #[test]
    fn nothing_opens_with_another_key_kind_or_name() {
        let (recorded, other) = (id("recorded-1"), id("recorded-2"));
        let sealed = seal(&key(1), meeting(&recorded), CLEAR).unwrap();
        assert_eq!(open(&key(2), meeting(&recorded), &sealed), Err(SecretError::Forged));
        assert_eq!(open(&key(1), meeting(&other), &sealed), Err(SecretError::Forged));
        assert_eq!(open(&key(1), Kind::Index, &sealed), Err(SecretError::Forged));
        let index = seal(&key(1), Kind::Index, CLEAR).unwrap();
        assert_eq!(open(&key(1), meeting(&recorded), &index), Err(SecretError::Forged));
    }

    #[test]
    fn one_bit_changed_anywhere_or_a_newer_seal_is_refused() {
        let recorded = id("recorded-1");
        let sealed = seal(&key(1), meeting(&recorded), CLEAR).unwrap();
        for at in 0..sealed.len() {
            let mut changed = sealed.clone();
            changed[at] ^= 1;
            assert_eq!(open(&key(1), meeting(&recorded), &changed), Err(SecretError::Forged), "byte {at}");
        }
        for cut in [0, MARK_BYTES, HEADER_BYTES, sealed.len() - 1] {
            assert_eq!(open(&key(1), meeting(&recorded), &sealed[..cut]), Err(SecretError::Forged));
        }
        let newer = [b"NTTMEET2".as_slice(), &sealed[MARK_BYTES..]].concat();
        assert_eq!(open(&key(1), meeting(&recorded), &newer), Err(SecretError::Forged));
    }

    #[test]
    fn only_the_json_of_before_the_seal_is_clear() {
        for clear in [VERSION_2, b"{}", b" \n\t{\"version\":1}"] {
            assert!(is_clear(clear));
        }
        for not in [WITNESS, b"", b"   ", b"[]", b"NTTMEET1{"] {
            assert!(!is_clear(not));
        }
    }

    #[test]
    fn damaged_bytes_are_refused_never_a_panic() {
        let recorded = id("recorded-1");
        let index = seal(&key(1), Kind::Index, VERSION_2).unwrap();
        each_damaged(&[WITNESS, &index], 3_000, |file| {
            for kind in [meeting(&recorded), Kind::Index] {
                if let Ok(opened) = open(&key(1), kind, file) {
                    assert_eq!(opened, VERSION_2, "only the very bytes sealed open");
                }
            }
        });
    }
}
