#!/bin/sh
# Builds the Rust core for the iPhone and generates the Swift that calls it. Xcode runs this script before it
# compiles. `./notitia ios` and the CI also run it once before Xcode: Xcode lists the Swift files of a build
# before any script runs, so the very first build would miss the generated file.
set -eu

IOS_APP="$(cd "$(dirname "$0")" && pwd)"
OUT="$IOS_APP/build/core"
GENERATED="$IOS_APP/iosApp/Generated"

# Xcode starts its scripts without the PATH of a terminal.
PATH="$HOME/.cargo/bin:$PATH"
if ! command -v cargo >/dev/null 2>&1; then
  echo "error: Rust introuvable. Installe-le depuis https://rustup.rs, puis relance le build." >&2
  exit 1
fi

# Xcode points SDKROOT at the iPhone SDK, which would break the build of the generator, a Mac program.
unset SDKROOT

case "${PLATFORM_NAME:-iphonesimulator}" in
  iphoneos) TARGET=aarch64-apple-ios ;;
  *) TARGET=aarch64-apple-ios-sim ;;
esac

cd "$IOS_APP/../../core"

# The static library alone: next to a dynamic one, the linker would pick the dynamic one, which the app does not embed.
cargo rustc --release --target "$TARGET" --crate-type staticlib

mkdir -p "$OUT" "$GENERATED"
cp "target/$TARGET/release/libnotitia_core.a" "$OUT/libnotitia_core.a"
cargo run --quiet -p uniffi-bindgen -- generate "$OUT/libnotitia_core.a" --language swift --no-format --out-dir "$OUT"

# Only when it changed, so Xcode does not recompile the app at every build.
if ! cmp -s "$OUT/notitia_core.swift" "$GENERATED/notitia_core.swift"; then
  cp "$OUT/notitia_core.swift" "$GENERATED/notitia_core.swift"
fi
