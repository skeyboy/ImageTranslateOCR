#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [[ -z "$ndk_home" ]]; then
  echo "ANDROID_NDK_HOME (or ANDROID_NDK) must point to an installed Android NDK" >&2
  exit 1
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is required: cargo install cargo-ndk" >&2
  exit 1
fi

rustup target add aarch64-linux-android
cd "$repo_root/demo-server/v4-service"
cargo ndk \
  --target arm64-v8a \
  --platform 24 \
  --output-dir "$repo_root/v4-translation-android/src/main/jniLibs" \
  build --release --locked --features android-jni
