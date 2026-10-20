#!/usr/bin/env bash
# Cross-compiles stegpony-ffi from StegPonyCore for Android and generates the
# Kotlin UniFFI bindings into the :core module. Run from the repo root:
#   bash scripts/build-core.sh
# Needs rustup, cargo-ndk (cargo install cargo-ndk), python3, and the NDK named
# in gradle.properties (Android Studio's SDK manager, or ANDROID_NDK_HOME).
#
# Which StegPonyCore: STEGPONY_CORE if set, else a sibling checkout at
# ../StegPonyCore (core edits show up on the next build), else the submodule at
# third_party/stegponycore (fresh clones, CI, F-Droid, the release harness).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -n "${STEGPONY_CORE:-}" ]]; then
  CORE="$STEGPONY_CORE"
elif [[ -f "$REPO/../StegPonyCore/crates/stegpony-ffi/Cargo.toml" ]]; then
  CORE="$(cd "$REPO/../StegPonyCore" && pwd)"
else
  CORE="$REPO/third_party/stegponycore"
fi

[[ -f "$CORE/crates/stegpony-ffi/Cargo.toml" ]] || {
  echo "StegPonyCore not found at $CORE. Run: git submodule update --init --recursive (or set STEGPONY_CORE)"; exit 1; }

command -v cargo >/dev/null || { echo "cargo not found; install rustup first"; exit 1; }
command -v cargo-ndk >/dev/null || { echo "cargo-ndk not found; run: cargo install cargo-ndk"; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_VERSION="$(sed -n 's/^ndkVersion=//p' "$REPO/gradle.properties")"
  [[ -n "$NDK_VERSION" ]] || { echo "Could not read ndkVersion from gradle.properties"; exit 1; }
  ANDROID_NDK_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/$NDK_VERSION"
  [[ -d "$ANDROID_NDK_HOME" ]] || {
    echo "NDK $NDK_VERSION not found at $ANDROID_NDK_HOME; install it with the SDK manager or set ANDROID_NDK_HOME"; exit 1; }
fi

# cargo-ndk passes ANDROID_NDK_HOME through as given, and the path can end up
# in the library, so build through a fixed symlink: the same bytes come out
# wherever the NDK is installed.
NDK_LINK="/tmp/stegpony-ndk"
[[ -L "$NDK_LINK" || ! -e "$NDK_LINK" ]] || { echo "$NDK_LINK exists and is not a symlink; remove it"; exit 1; }
ln -sfn "$ANDROID_NDK_HOME" "$NDK_LINK"
export ANDROID_NDK_HOME="$NDK_LINK"

echo "StegPonyCore: $CORE ($(git -C "$CORE" rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout'))"

# Inside the core so rustup uses the toolchain its rust-toolchain.toml pins.
pushd "$CORE" >/dev/null
rustup target add aarch64-linux-android x86_64-linux-android

# Fixed paths in panic locations instead of this machine's checkout and home
# directory, and 16 KB page alignment for current Android devices.
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$CORE=/stegpony-core --remap-path-prefix=$HOME=/home${RUSTFLAGS:+ $RUSTFLAGS}"

JNI_OUT="$REPO/core/src/main/jniLibs"
echo "Cross-compiling stegpony-ffi for arm64-v8a and x86_64"
cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o "$JNI_OUT" \
  build --release --locked -p stegpony-ffi

# uniffi-bindgen reads the interface from metadata sections in the library. The
# release profile does not strip them, so the arm64 library serves. The
# generator is a host program: the Android linker flags above would break its
# build on macOS, and nothing from it ships, so it builds without them.
BINDINGS_OUT="$(mktemp -d)"
env -u RUSTFLAGS cargo run -q --locked -p stegpony-ffi --features cli --bin uniffi-bindgen -- \
  generate --library "$JNI_OUT/arm64-v8a/libstegpony_ffi.so" \
  --language kotlin --no-format --out-dir "$BINDINGS_OUT"
popd >/dev/null

KOTLIN_OUT="$REPO/core/src/main/kotlin"
rm -rf "$KOTLIN_OUT/uniffi"
mkdir -p "$KOTLIN_OUT"
cp -r "$BINDINGS_OUT/uniffi" "$KOTLIN_OUT/"
rm -rf "$BINDINGS_OUT"

echo "Done:"
echo "  $JNI_OUT/arm64-v8a/libstegpony_ffi.so"
echo "  $JNI_OUT/x86_64/libstegpony_ffi.so"
echo "  $KOTLIN_OUT/uniffi/stegpony_ffi/stegpony_ffi.kt"
