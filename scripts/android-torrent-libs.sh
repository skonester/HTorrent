#!/usr/bin/env bash
# Builds the torrent engine for Android (arm64, arm, x64) and puts it where Gradle packages it, with its notices.
#   bash scripts/android-torrent-libs.sh arm64 arm
set -euo pipefail
cd "$(dirname "$0")/.."
declare -A ABI=([arm64]=arm64-v8a [arm]=armeabi-v7a [x64]=x86_64)
for t in "$@"; do
  python3 scripts/build-torrent-native.py "android-$t"
  dest="android/app/src/main/jniLibs/${ABI[$t]}"
  mkdir -p "$dest"
  cp ".native-cache/sdk/android-$t/lib/libnexonic_torrent.so" "$dest/"
done
first="$1"
notices=android/app/src/main/assets/licenses/Torrent-engine-NOTICES.txt
{
  echo "Torrent engine: libtorrent and the libraries it uses (Boost, OpenSSL and others), built from source."
  for f in .native-cache/sdk/android-$first/notices/*.txt; do echo; echo "== $(basename "$f" .txt)"; cat "$f"; done
} > "$notices"
ls -la android/app/src/main/jniLibs/*/libnexonic_torrent.so
