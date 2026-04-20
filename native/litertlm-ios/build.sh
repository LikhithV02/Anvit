#!/usr/bin/env bash
# Build LiteRT-LM as a universal XCFramework for iOS device + simulator.
#
# Prerequisites (run once):
#   1. Install Xcode from the App Store (not just Command Line Tools)
#   2. sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
#   3. brew install bazelisk
#
# Usage:
#   cd AgenticRAG/native/litertlm-ios
#   ./build.sh
#
# Output:
#   LiteRtLM.xcframework/   — drop this into Xcode and the KMP build

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
XCFW_OUT="$SCRIPT_DIR/LiteRtLM.xcframework"
SOURCE_DIR="$BUILD_DIR/LiteRT-LM"

LITERT_LM_REPO="https://github.com/google-ai-edge/LiteRT-LM.git"
LITERT_LM_TAG="v0.10.2"

BAZEL_TARGET="//c:engine_cpu"

# Route all Bazel output (~15-20 GB) to an APFS disk image on the external SSD.
# ExFAT (the Crucial SSD native format) doesn't support POSIX symlinks/permissions
# that Bazel requires — so we use a sparse APFS image hosted on the SSD instead.
#
# One-time setup (already done if BazelCache is mounted):
#   hdiutil create -size 40g -fs APFS -type SPARSE -volname "BazelCache" \
#     "/Volumes/Crucial SSD/bazel-cache"
#   hdiutil attach "/Volumes/Crucial SSD/bazel-cache.sparseimage"
APFS_IMAGE="/Volumes/Crucial SSD/bazel-cache.sparseimage"
BAZEL_OUTPUT_BASE="/Volumes/BazelCache/bazel-litertlm"

# Auto-mount the APFS image if not already mounted
if [[ ! -d "/Volumes/BazelCache" ]]; then
  echo "Mounting APFS cache image..."
  hdiutil attach "$APFS_IMAGE"
fi

BAZEL="bazel --output_base=\"$BAZEL_OUTPUT_BASE\""

# ── 0. Guard: Xcode must be the active developer directory ──────────────────
XCODE_PATH="$(xcode-select -p 2>/dev/null || true)"
if [[ "$XCODE_PATH" != */Xcode.app/* ]]; then
  echo "ERROR: Xcode not set as active developer directory."
  echo "  Install Xcode from the App Store, then run:"
  echo "  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
  exit 1
fi
XCODE_VERSION=$(xcodebuild -version | head -1)
echo "Using $XCODE_VERSION at $XCODE_PATH"

# ── 1. Clone LiteRT-LM source ───────────────────────────────────────────────
mkdir -p "$BUILD_DIR"
if [[ ! -d "$SOURCE_DIR/.git" ]]; then
  echo "Cloning LiteRT-LM $LITERT_LM_TAG..."
  git clone --depth=1 --branch "$LITERT_LM_TAG" "$LITERT_LM_REPO" "$SOURCE_DIR"
else
  echo "LiteRT-LM source already present at $SOURCE_DIR"
fi

cd "$SOURCE_DIR"

# ── 2. Build for iOS device (arm64) ─────────────────────────────────────────
echo ""
echo "Building for ios_arm64 (device)..."
eval "$BAZEL" build --config=ios_arm64 "$BAZEL_TARGET" \
  --features=-use_header_modules \
  --output_groups=archive_manifest
DEVICE_A=$(find "$BAZEL_OUTPUT_BASE" -name "libengine_cpu.a" -path "*ios_arm64*" 2>/dev/null | head -1)
if [[ -z "$DEVICE_A" ]]; then
  DEVICE_A=$(find "$BAZEL_OUTPUT_BASE" -name "libengine*.a" -path "*ios_arm64*" 2>/dev/null | head -1)
fi
echo "Device archive: $DEVICE_A"
mkdir -p "$BUILD_DIR/ios-arm64"
cp "$DEVICE_A" "$BUILD_DIR/ios-arm64/libLiteRtLM.a"

# ── 3. Build for iOS simulator (arm64) ──────────────────────────────────────
echo ""
echo "Building for ios_sim_arm64 (simulator)..."
eval "$BAZEL" build --config=ios_sim_arm64 "$BAZEL_TARGET" \
  --features=-use_header_modules \
  --output_groups=archive_manifest
SIM_A=$(find "$BAZEL_OUTPUT_BASE" -name "libengine_cpu.a" -path "*ios_sim_arm64*" 2>/dev/null | head -1)
if [[ -z "$SIM_A" ]]; then
  SIM_A=$(find "$BAZEL_OUTPUT_BASE" -name "libengine*.a" -path "*ios_sim_arm64*" 2>/dev/null | head -1)
fi
echo "Simulator archive: $SIM_A"
mkdir -p "$BUILD_DIR/ios-arm64-simulator"
cp "$SIM_A" "$BUILD_DIR/ios-arm64-simulator/libLiteRtLM.a"

# ── 4. Copy headers ──────────────────────────────────────────────────────────
cp "$SOURCE_DIR/c/engine.h" "$SCRIPT_DIR/headers/engine.h"

# ── 5. Package as XCFramework ────────────────────────────────────────────────
echo ""
echo "Creating XCFramework..."
rm -rf "$XCFW_OUT"

# Stub out module.modulemap so Xcode sees this as a clang module
for SLICE_DIR in "$BUILD_DIR/ios-arm64" "$BUILD_DIR/ios-arm64-simulator"; do
  mkdir -p "$SLICE_DIR/Headers"
  cp "$SCRIPT_DIR/headers/engine.h" "$SLICE_DIR/Headers/"
  cat > "$SLICE_DIR/Headers/module.modulemap" <<'MODULEMAP'
module LiteRtLM {
  header "engine.h"
  export *
}
MODULEMAP
done

xcodebuild -create-xcframework \
  -library "$BUILD_DIR/ios-arm64/libLiteRtLM.a"         -headers "$BUILD_DIR/ios-arm64/Headers" \
  -library "$BUILD_DIR/ios-arm64-simulator/libLiteRtLM.a" -headers "$BUILD_DIR/ios-arm64-simulator/Headers" \
  -output "$XCFW_OUT"

echo ""
echo "SUCCESS: $XCFW_OUT"
echo ""
echo "Next steps:"
echo "  1. The XCFramework is at: $XCFW_OUT"
echo "  2. composeApp/build.gradle.kts references it via the litertlm cinterop"
echo "  3. Run: ./gradlew :composeApp:cinteropLitertlmIosArm64 to verify Kotlin bindings"
