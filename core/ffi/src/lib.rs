uniffi::setup_scaffolding!();

// A module of its own, as when it lived in the core: UniFFI hashes the path of each function into the checksums the
// apps check at launch, which stay the same.
mod bridge;
