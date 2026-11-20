use std::fmt;

use chacha20poly1305::aead::{AeadInOut, KeyInit};
use chacha20poly1305::{Tag, XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::Zeroizing;

// What the core keeps is sealed here, and this is the only file that knows which libraries do it: XChaCha20-Poly1305
// under a key drawn by HKDF-SHA256, both standard, so another library opens what this one sealed.

pub const KEY_BYTES: usize = 32;
pub(crate) const SALT_BYTES: usize = 32;
const NONCE_BYTES: usize = 24;
const TAG_BYTES: usize = 16;
// What sealing adds to the bytes it is given.
pub(crate) const SEAL_BYTES: usize = NONCE_BYTES + TAG_BYTES;

// Says nothing of the key nor of the bytes: an error may end up in a log (product rule 1).
#[derive(Debug, PartialEq)]
pub enum SecretError {
    KeySize,
    // The system gave no random bytes: nothing is sealed with a nonce that could come twice.
    NoRandom,
    // Not sealed with this key, not about this, or changed since. A seal cannot tell which.
    Forged,
}

impl fmt::Display for SecretError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            SecretError::KeySize => "a key is 32 bytes long",
            SecretError::NoRandom => "the system gave no random bytes",
            SecretError::Forged => "the bytes were not sealed with this key, or were changed",
        })
    }
}

impl std::error::Error for SecretError {}

pub(crate) fn random<const N: usize>() -> Result<[u8; N], SecretError> {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).map_err(|_| SecretError::NoRandom)?;
    Ok(bytes)
}

// The key of the app, which its system keeps (Keystore, Keychain). No Debug, so no log can print one, and wiped
// from memory when it is dropped.
pub struct Key(Zeroizing<[u8; KEY_BYTES]>);

impl Key {
    pub fn new(bytes: &[u8]) -> Result<Self, SecretError> {
        <[u8; KEY_BYTES]>::try_from(bytes).map(|key| Self(Zeroizing::new(key))).map_err(|_| SecretError::KeySize)
    }

    // As an app hands it over: the bytes it was given are wiped, whether they make a key or not.
    pub fn take(bytes: Vec<u8>) -> Result<Self, SecretError> {
        Self::new(&Zeroizing::new(bytes))
    }

    // A key of its own for each file, and for each kind of file: handing one meeting to a linked device then gives
    // that key away and never this one.
    pub(crate) fn derive(&self, purpose: &str, salt: &[u8; SALT_BYTES]) -> Result<Seal, SecretError> {
        let mut key = Zeroizing::new([0; KEY_BYTES]);
        let drawn = Hkdf::<Sha256>::new(Some(salt), self.0.as_slice()).expand(purpose.as_bytes(), key.as_mut_slice());
        drawn.map_err(|_| SecretError::KeySize)?;
        XChaCha20Poly1305::new_from_slice(key.as_slice()).map(Seal).map_err(|_| SecretError::KeySize)
    }
}

pub(crate) struct Seal(XChaCha20Poly1305);

impl Seal {
    // The nonce, the bytes made unreadable, then the tag. `about` is not kept: the same must be given to open
    // them, so bytes moved to another place or another file are refused there.
    pub fn seal(&self, about: &[u8], clear: &[u8]) -> Result<Vec<u8>, SecretError> {
        // Drawn and never counted: a count starts again when a file cut by a crash is written again, and a nonce
        // used twice under one key gives both messages away.
        self.seal_with(random()?, about, clear)
    }

    fn seal_with(&self, nonce: [u8; NONCE_BYTES], about: &[u8], clear: &[u8]) -> Result<Vec<u8>, SecretError> {
        let mut sealed = [nonce.as_slice(), clear].concat();
        let hidden = &mut sealed[NONCE_BYTES..];
        let tag = self.0.encrypt_inout_detached(&XNonce::from(nonce), about, hidden.into());
        sealed.extend(tag.map_err(|_| SecretError::Forged)?);
        Ok(sealed)
    }

    pub fn open(&self, about: &[u8], sealed: &[u8]) -> Result<Vec<u8>, SecretError> {
        let hidden = sealed.len().checked_sub(SEAL_BYTES).ok_or(SecretError::Forged)?;
        let (nonce, rest) = sealed.split_at(NONCE_BYTES);
        let (hidden, tag) = rest.split_at(hidden);
        let nonce = XNonce::try_from(nonce).map_err(|_| SecretError::Forged)?;
        let tag = Tag::try_from(tag).map_err(|_| SecretError::Forged)?;
        let mut clear = hidden.to_vec();
        let opened = self.0.decrypt_inout_detached(&nonce, about, clear.as_mut_slice().into(), &tag);
        opened.map(|()| clear).map_err(|_| SecretError::Forged)
    }
}

#[cfg(test)]
pub(crate) mod testing {
    use sha2::Digest;

    use super::*;

    // Made up, like every key of the tests.
    pub fn key(byte: u8) -> Key {
        Key::new(&[byte; KEY_BYTES]).unwrap()
    }

    pub fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }

    // SHA-256, as sha256sum writes it: a file is checked without the core.
    pub fn fingerprint(bytes: &[u8]) -> String {
        hex(&Sha256::digest(bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::testing::{hex, key};
    use super::*;

    const SALT: [u8; SALT_BYTES] = [7; SALT_BYTES];
    const CLEAR: &[u8] = b"made up for the tests";

    fn seal() -> Seal {
        key(1).derive("notitia test 1", &SALT).unwrap()
    }

    #[test]
    fn sealed_bytes_open_with_the_same_key_about_the_same_thing() {
        let sealed = seal().seal(b"second 4", CLEAR).unwrap();
        assert_eq!(sealed.len(), CLEAR.len() + SEAL_BYTES);
        assert_eq!(seal().open(b"second 4", &sealed).unwrap(), CLEAR);
    }

    // What libsodium gives for the same key, salt, purpose, nonce and bytes: a file sealed today opens with any
    // library, and with this one whatever version it moves to.
    #[test]
    fn the_seal_is_the_standard_one() {
        let by_libsodium = "090909090909090909090909090909090909090909090909f6065ff63c38b47b54bd0fbb3d11adf39de889886b\
                            b7d66559d690f89eb035c53c1afa91d4";
        let sealed = seal().seal_with([9; NONCE_BYTES], b"second 4", CLEAR).unwrap();
        assert_eq!(hex(&sealed), by_libsodium);
    }

    #[test]
    fn nothing_opens_with_another_key_salt_purpose_or_place() {
        let sealed = seal().seal(b"second 4", CLEAR).unwrap();
        let others = [
            key(2).derive("notitia test 1", &SALT).unwrap(),
            key(1).derive("notitia test 1", &[8; SALT_BYTES]).unwrap(),
            key(1).derive("notitia test 2", &SALT).unwrap(),
        ];
        for other in others {
            assert_eq!(other.open(b"second 4", &sealed), Err(SecretError::Forged));
        }
        assert_eq!(seal().open(b"second 5", &sealed), Err(SecretError::Forged));
    }

    #[test]
    fn one_bit_changed_anywhere_is_refused() {
        let sealed = seal().seal(b"", CLEAR).unwrap();
        for at in 0..sealed.len() {
            let mut changed = sealed.clone();
            changed[at] ^= 1;
            assert_eq!(seal().open(b"", &changed), Err(SecretError::Forged));
        }
        for cut in [0, NONCE_BYTES, SEAL_BYTES - 1, sealed.len() - 1] {
            assert_eq!(seal().open(b"", &sealed[..cut]), Err(SecretError::Forged));
        }
    }

    #[test]
    fn the_same_bytes_sealed_twice_have_nothing_in_common() {
        let (first, second) = (seal().seal(b"", CLEAR).unwrap(), seal().seal(b"", CLEAR).unwrap());
        assert_ne!(first[..NONCE_BYTES], second[..NONCE_BYTES]);
        assert_ne!(first[NONCE_BYTES..], second[NONCE_BYTES..]);
        assert!(!first.windows(CLEAR.len()).any(|window| window == CLEAR));
    }

    #[test]
    fn a_key_is_32_bytes_long() {
        assert!(Key::new(&[1; KEY_BYTES]).is_ok());
        for length in [0, KEY_BYTES - 1, KEY_BYTES + 1] {
            assert_eq!(Key::new(&vec![1; length]).err(), Some(SecretError::KeySize));
        }
    }

    #[test]
    fn a_key_handed_over_is_the_key_of_its_bytes() {
        let taken = Key::take(vec![1; KEY_BYTES]).unwrap().derive("notitia test 1", &SALT).unwrap();
        assert_eq!(taken.open(b"", &seal().seal(b"", CLEAR).unwrap()).unwrap(), CLEAR);
        assert_eq!(Key::take(vec![1; KEY_BYTES - 1]).err(), Some(SecretError::KeySize));
    }
}
