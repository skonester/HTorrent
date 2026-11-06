#!/usr/bin/env bash
# Takes the Android screenshots in a running emulator (used by the Android screenshots workflow).
# Needs scripts/demo-server.py on port 8099 and android/demo served on port 8098, both on the host.
set -euo pipefail
APK=android/app/build/outputs/apk/debug/app-debug.apk
PKG=com.htorrent.mobile.debug
ACT=$PKG/com.htorrent.mobile.MainActivity
adb install -r "$APK"
# A clean status bar: fixed time, full battery and signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
mkdir -p shots
shot() {
  local name=$1 screen=$2 wait=$3
  adb shell am force-stop "$PKG"
  adb shell pm clear "$PKG" >/dev/null
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb logcat -b all -c || true
  adb shell am start -n "$ACT" --es ndm_demo 1 --es ndm_screen "$screen" >/dev/null
  sleep "$wait"
  adb exec-out screencap -p > "shots/android-$name.png"
  echo "android-$name.png $(stat -c %s "shots/android-$name.png") bytes"
  # A crash fails the run and shows why.
  if adb logcat -b crash -d | grep -q "$PKG"; then
    echo "The app crashed on the $screen screen:"; adb logcat -b crash -d | tail -60; exit 1
  fi
}
shot main downloads 10
shot detail detail 10
shot add add 9
shot browser browser 14
shot found found 16
shot settings settings 7
adb shell cmd uimode night yes
shot main-dark downloads 10
adb shell cmd uimode night no
adb shell am force-stop "$PKG"
