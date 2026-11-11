#!/usr/bin/env bash
set -euo pipefail

HOTAUDIO_REF="${1:-v2.3.0}"
WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
BUILD_DIR="${RUNNER_TEMP:-/tmp}/hotaudio-build"
HEADERS_DIR="${RUNNER_TEMP:-/tmp}/hotaudio-headers"
DEST_FRAMEWORKS="$WORKSPACE/iosApp/Frameworks"
DEST_SWIFT="$WORKSPACE/iosApp/iosApp"

echo "Building Hotaudio XCFramework for ref: $HOTAUDIO_REF"

rm -rf "$BUILD_DIR" "$HEADERS_DIR"
mkdir -p "$BUILD_DIR" "$HEADERS_DIR" "$DEST_FRAMEWORKS" "$DEST_SWIFT"

echo "Cloning hotaudio-extractor $HOTAUDIO_REF..."
git clone --depth 1 --branch "$HOTAUDIO_REF" \
  https://github.com/dawn-used-yeet-alt/hotaudio-extractor.git \
  "$BUILD_DIR"

cd "$BUILD_DIR"

echo "Building UniFFI bindings..."
bash scripts/build-bindings.sh --release

echo "Building libhotaudio_ffi.a for aarch64-apple-ios-sim..."
cargo build --manifest-path bindings/Cargo.toml --locked --release -p hotaudio-ffi \
  --target aarch64-apple-ios-sim

echo "Building libhotaudio_ffi.a for aarch64-apple-ios (device)..."
cargo build --manifest-path bindings/Cargo.toml --locked --release -p hotaudio-ffi \
  --target aarch64-apple-ios

cp bindings/out/HotaudioFFI.h "$HEADERS_DIR/HotaudioFFI.h"
cp bindings/out/HotaudioFFI.modulemap "$HEADERS_DIR/module.modulemap"

echo "Creating dual-slice HotaudioFFI.xcframework..."
rm -rf "$DEST_FRAMEWORKS/HotaudioFFI.xcframework"
xcodebuild -create-xcframework \
  -library bindings/target/aarch64-apple-ios-sim/release/libhotaudio_ffi.a \
  -headers "$HEADERS_DIR" \
  -library bindings/target/aarch64-apple-ios/release/libhotaudio_ffi.a \
  -headers "$HEADERS_DIR" \
  -output "$DEST_FRAMEWORKS/HotaudioFFI.xcframework"

echo "Copying generated Hotaudio.swift..."
cp bindings/out/Hotaudio.swift "$DEST_SWIFT/Hotaudio.swift"

echo "Hotaudio XCFramework and Swift bindings generated successfully."
