#!/usr/bin/env bash
# Builds Android-GPL-Sources.tar.gz: the source code of the GPL video engine in the Android app (youtubedl-android
# with its Python and FFmpeg) and of every library those are built with, as GPL-3.0 section 6 asks of anyone who
# distributes the app. HTorrent's own source is the release's "Source code" archive.
#   scripts/android-gpl-sources.sh OUT_DIR        (DRY=1 lists the components without downloading them)
set -euo pipefail
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
YTDL_TAG=0.18.1   # io.github.junkfood02.youtubedl-android in android/app/build.gradle.kts
# termux-packages on the day youtubedl-android bumped FFmpeg 7.1.1 and Python 3.12.11 (the versions in 0.18.1).
TERMUX_COMMIT=8ddd9a7fc8e06330545487d81bb7591cb40f1aa6
[ -n "${DRY:-}" ] || grep -q "youtubedlAndroid = \"$YTDL_TAG\"" android/app/build.gradle.kts || { echo "youtubedl-android version changed: update $0" >&2; exit 1; }

WORK="$(mktemp -d)"
ROOT="$WORK/Android-GPL-Sources"
mkdir -p "$ROOT/upstream"
fetch() { curl -fsSL --retry 4 --retry-all-errors --retry-delay 5 --connect-timeout 20 -o "$2" "$1" && usable "$2"; }
usable() { # the recipe's file, or at least a real source archive (not an error or challenge page served with status 200)
  if [ -n "${WANT:-}" ] && [ "$WANT" != SKIP_CHECKSUM ] && [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$WANT" ]; then return 0; fi
  tar -tf "$1" >/dev/null 2>&1 || unzip -tq "$1" >/dev/null 2>&1
}
get() { # the URL, then known mirrors for hosts that are often down
  fetch "$1" "$2" && return 0
  case "$1" in
    *ffmpeg.org/releases/ffmpeg-*)
      v="$(basename "$1" | sed -E 's/^ffmpeg-(.*)\.tar\.[a-z0-9]+$/\1/')"
      fetch "https://ffmpeg.org/releases/$(basename "$1")" "$2" \
        || fetch "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$v.tar.gz" "$2" ;;
    *linuxtv.org/downloads/v4l-utils/v4l-utils-*)
      v="$(basename "$1" | sed -E 's/^v4l-utils-(.*)\.tar\.[a-z0-9]+$/\1/')"
      fetch "https://www.linuxtv.org/downloads/v4l-utils/$(basename "$1")" "$2" \
        || fetch "https://github.com/gjasny/v4l-utils/archive/refs/tags/v4l-utils-$v.tar.gz" "$2" ;;
    *fossies.org/*/lzo-*) fetch "https://www.oberhumer.com/opensource/lzo/download/$(basename "$1" .tar.xz).tar.gz" "$2" ;;
    *downloads.sourceforge.net/project/giflib/giflib-5.*)
      # Upstream moved the 5.x releases into a subdirectory in 2026.
      fetch "https://downloads.sourceforge.net/project/giflib/giflib-5.x/$(basename "$1")" "$2" \
        || fetch "https://mirrors.omnios.org/libgif/$(basename "$1")" "$2" ;;
    *downloads.sourceforge.net/project/*) fetch "https://master.dl.sourceforge.net/project/${1#*downloads.sourceforge.net/project/}?viasf=1" "$2" ;;
    *) sleep 20; fetch "$1" "$2" ;;  # one more try after a pause
  esac
}

# 1. youtubedl-android at the released tag (GPL-3.0).
[ -n "${DRY:-}" ] || git -c advice.detachedHead=false clone -q --depth 1 --branch "$YTDL_TAG" https://github.com/yausername/youtubedl-android.git "$ROOT/youtubedl-android"
rm -rf "$ROOT/youtubedl-android/.git"

# 2. The Termux build recipes (scripts and patches) used for its Python and FFmpeg.
git init -q "$WORK/termux" && git -C "$WORK/termux" fetch -q --depth 1 https://github.com/termux/termux-packages.git "$TERMUX_COMMIT"
git -C "$WORK/termux" checkout -q FETCH_HEAD
mkdir -p "$ROOT/termux-packages"
cp -R "$WORK/termux/scripts" "$WORK/termux/build-package.sh" "$WORK/termux/LICENSE.md" "$ROOT/termux-packages/" 2>/dev/null || true

# 3. Python, FFmpeg and everything they depend on at run time, with each project's original source archive.
dir_of() { # the recipe folder of a package; a subpackage (name.subpackage.sh) belongs to its parent's recipe
  local d f
  for d in packages root-packages x11-packages; do [ -f "$WORK/termux/$d/$1/build.sh" ] && { echo "$d/$1"; return 0; }; done
  f="$(cd "$WORK/termux" && ls packages/*/"$1".subpackage.sh root-packages/*/"$1".subpackage.sh x11-packages/*/"$1".subpackage.sh 2>/dev/null | head -1 || true)"
  if [ -n "$f" ]; then dirname "$f"; fi
  return 0
}
recipe() { # prints SRCURL, SHA256 and DEPENDS of a recipe, one value per line, tab separated
  ( cd "$WORK/termux/$1"
    TERMUX_PKG_BUILDER_DIR="$PWD" TERMUX_PREFIX=/data/data/com.termux/files/usr TERMUX_ARCH=aarch64 TERMUX_PKG_API_LEVEL=24
    TERMUX_ARCH_BITS=64 TERMUX_HOST_PLATFORM=aarch64-linux-android TERMUX_SCRIPTDIR="$WORK/termux"
    set +eu; source ./build.sh >/dev/null 2>&1
    for u in "${TERMUX_PKG_SRCURL[@]}"; do printf 'url\t%s\n' "$u"; done
    for s in "${TERMUX_PKG_SHA256[@]}"; do printf 'sha\t%s\n' "$s"; done
    printf 'dep\t%s\n' "${TERMUX_PKG_DEPENDS:-}" )
}
declare -A seen=()
queue=(ffmpeg python)
: > "$ROOT/COMPONENTS.txt"
while [ ${#queue[@]} -gt 0 ]; do
  name="${queue[0]}"; queue=("${queue[@]:1}")
  [ -n "${seen[$name]:-}" ] && continue; seen[$name]=1
  # libc++ comes from the Android NDK (LLVM, Apache-2.0 with LLVM exception, not GPL); its recipe downloads the whole
  # NDK, which is a toolchain, not source. Point to it instead of copying it.
  case "$name" in libc++|ndk-sysroot|ndk-multilib)
    echo "$name: Android NDK (LLVM libc++, Apache-2.0 WITH LLVM-exception), source at https://github.com/llvm/llvm-project" >> "$ROOT/COMPONENTS.txt"; continue ;;
  esac
  d="$(dir_of "$name")"
  [ -n "$d" ] && [ -n "${seen[dir:$d]:-}" ] && continue
  [ -n "$d" ] && seen[dir:$d]=1
  if [ -z "$d" ]; then echo "$name: part of Android or the NDK, no separate source" >> "$ROOT/COMPONENTS.txt"; continue; fi
  mkdir -p "$ROOT/termux-packages/$(dirname "$d")"; cp -R "$WORK/termux/$d" "$ROOT/termux-packages/$d"
  mapfile -t urls < <(recipe "$d" | awk -F'\t' '$1=="url"{print $2}')
  mapfile -t sums < <(recipe "$d" | awk -F'\t' '$1=="sha"{print $2}')
  deps="$(recipe "$d" | awk -F'\t' '$1=="dep"{print $2}')"
  mkdir -p "$ROOT/upstream/$name"
  for i in "${!urls[@]}"; do
    u="${urls[$i]}"
    if [ -n "${DRY:-}" ]; then echo "$name: $u" >> "$ROOT/COMPONENTS.txt"; continue; fi
    case "$u" in
      git+*) if git clone -q --depth 1 "${u#git+}" "$ROOT/upstream/$name/$(basename "${u%.git}")"; then rm -rf "$ROOT/upstream/$name"/*/.git
             else echo "::warning::Could not clone $u"; echo "$name: NOT INCLUDED (clone failed, get it from $u)" >> "$ROOT/COMPONENTS.txt"; continue; fi ;;
      *) f="$ROOT/upstream/$name/$(basename "${u%%\?*}")"
         WANT="${sums[$i]:-}"
         if ! get "$u" "$f"; then
           echo "::warning::Could not download $u"; echo "$name: NOT INCLUDED (download failed, get it from $u)" >> "$ROOT/COMPONENTS.txt"; continue
         fi
         want="${sums[$i]:-}"
         if [ -n "$want" ] && [ "$want" != SKIP_CHECKSUM ]; then
           got="$(sha256sum "$f" | cut -d' ' -f1)"
           # Upstream sometimes re-packs an archive (it is a verified archive by now); keep it, but say so
           # (the recipe's checksum is in termux-packages/).
           [ "$got" = "$want" ] || { echo "::warning::Checksum differs from the recipe: $u"; echo "$name: checksum differs from the recipe ($got)" >> "$ROOT/COMPONENTS.txt"; }
         fi ;;
    esac
    echo "$name: $u" >> "$ROOT/COMPONENTS.txt"
  done
  # "a, b (>= 1), c | d" -> a b c (the first choice of each alternative).
  for dep in $(echo "$deps" | tr ',' '\n' | sed 's/|.*//; s/(.*)//' | tr -d ' '); do queue+=("$dep"); done
done

if [ -n "${DRY:-}" ]; then cat "$ROOT/COMPONENTS.txt"; rm -rf "$WORK"; exit 0; fi

cat > "$ROOT/README.txt" <<TXT
Source code for the GPL-3.0 parts of HTorrent for Android

The Android app includes youtubedl-android $YTDL_TAG (GPL-3.0) with Python and FFmpeg built from Termux packages.
This archive holds:
- youtubedl-android/: its source at tag $YTDL_TAG (https://github.com/yausername/youtubedl-android),
  with BUILD_PYTHON.md and BUILD_FFMPEG.md describing how its bundled files are built;
- termux-packages/: the Termux build scripts, recipes and patches at commit $TERMUX_COMMIT
  (https://github.com/termux/termux-packages), the recipes for Python, FFmpeg and their run-time libraries;
- upstream/: the original source archive of each of those projects, as the recipes download them (checksums
  checked against the recipes);
- COMPONENTS.txt: every component and where its source came from.
HTorrent's own source code is the "Source code" archive of the same release, or the matching tag at
https://github.com/skonester/HTorrent. Questions: open an issue in that repository.
TXT
if grep -q 'NOT INCLUDED' "$ROOT/COMPONENTS.txt"; then
  echo "::error::Source collection is incomplete; refusing to package an incomplete archive."
  grep 'NOT INCLUDED' "$ROOT/COMPONENTS.txt"
  exit 1
fi
tar -C "$WORK" -czf "$OUT/Android-GPL-Sources.tar.gz" Android-GPL-Sources
ls -la "$OUT/Android-GPL-Sources.tar.gz"
wc -l < "$ROOT/COMPONENTS.txt"
rm -rf "$WORK"
