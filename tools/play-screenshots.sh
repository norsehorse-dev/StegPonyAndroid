#!/usr/bin/env bash
# Store screenshots for every listing language, on one connected phone or emulator
# (Android 13 or later keeps the language switch flicker-free). Installs the debug
# build, uses its Demo hook (app/src/debug) to hide a real message in the photo you
# give it and pick screens, and writes
# fastlane/metadata/android/<locale>/images/phoneScreenshots/*.png. These are the
# plain captures F-Droid shows; Play reads the same folder.
#
#   tools/play-screenshots.sh [photo.jpg]
#
# The photo is the input, not where the screenshots go: with no argument it is
# tools/demo-cover.jpg. It is shown as the cover and its hidden-in copy as the result, so use one
# you took yourself and are happy to publish. It is copied to the debug app's cache
# on the phone and nowhere else. The app's data on the phone is cleared for each
# language; that is the debug package only, never an installed release.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.stegpony.android.debug
ACT=com.stegpony.android.MainActivity
OUT=fastlane/metadata/android
TMP=/data/local/tmp/stegpony-demo-cover

COVER=${1:-tools/demo-cover.jpg}
[ -f "$COVER" ] || { echo "No cover photo at $COVER. Put one at tools/demo-cover.jpg or pass its path."; exit 1; }
# The name the app shows for the cover on the review step.
NAME=photo.jpg

# Which device: ANDROID_SERIAL if it is set, else the only one connected, else
# the only emulator (so a phone can stay plugged in beside it).
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$(adb devices | grep -c 'device$')" != 1 ]; then
  emulators=$(adb devices | awk '$2 == "device" && $1 ~ /^emulator/ { print $1 }')
  if [ "$(printf '%s\n' "$emulators" | grep -c .)" = 1 ]; then
    ANDROID_SERIAL=$emulators
  else
    echo "Connect one phone or emulator, or set ANDROID_SERIAL to the one to use"
    exit 1
  fi
fi
[ -z "${ANDROID_SERIAL:-}" ] || { export ANDROID_SERIAL; echo "Using $ANDROID_SERIAL"; }

# Built by Gradle, installed by adb: Gradle's own install goes to every device
# attached and can sit waiting on a phone's install prompt.
./gradlew -q :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb push "$COVER" "$TMP" >/dev/null

NIGHT=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
case "$NIGHT" in yes|no|auto) ;; *) NIGHT=no ;; esac

demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

cleanup() {
  demo exit || true
  adb shell wm size reset || true
  adb shell wm density reset || true
  adb shell svc power stayon false || true
  adb shell cmd uimode night "$NIGHT" >/dev/null || true
  adb shell rm -f "$TMP" || true
}
trap cleanup EXIT

# Play rejects screenshots longer than twice their width, so render at 1080x1920 (9:16).
adb shell wm size 1080x1920
adb shell wm density 420
adb shell svc power stayon usb
# The dark theme is the one the listing shows; the phone's own setting is put back after.
adb shell cmd uimode night yes >/dev/null
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
sleep 2

adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true -e mobile show -e level 4 -e fully true -e datatype none
demo notifications -e visible false

# Seconds to let each screen settle. Hiding in a full-size photo is the slow one.
wait_for() {
  case "$1" in
    cover) echo 8 ;;
    result) echo 20 ;;
    reveal) echo 8 ;;
    keys) echo 5 ;;
    *) echo 3 ;;
  esac
}

for pair in en-US:en de-DE:de es-ES:es fr-FR:fr pt-BR:pt-BR ru-RU:ru zh-CN:zh-Hans; do
  dir=${pair%%:*}
  lang=${pair##*:}
  shots="$OUT/$dir/images/phoneScreenshots"
  mkdir -p "$shots"
  rm -f "$shots"/*.png
  adb shell am force-stop "$PKG"
  adb shell pm clear "$PKG" >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p cache/demo/cover && cat > cache/demo/cover/$NAME' < $TMP"
  adb shell am start -W -n "$PKG/$ACT" --es demo_lang "$lang" --es demo_screen tour >/dev/null
  sleep 6
  i=1
  for screen in tour cover review result reveal keys settings; do
    adb shell am start -W --activity-single-top -n "$PKG/$ACT" --es demo_screen "$screen" >/dev/null
    sleep "$(wait_for "$screen")"
    # The cover's preview is below the two choices: scroll it into view.
    if [ "$screen" = cover ]; then
      adb shell input swipe 540 1400 540 400 400
      adb shell input swipe 540 1400 540 400 400
      sleep 1
    fi
    adb exec-out screencap -p > "$shots/${i}_${screen}.png"
    if [ "$(wc -c < "$shots/${i}_${screen}.png")" -lt 40000 ]; then
      echo "$shots/${i}_${screen}.png is blank. Unlock the phone, keep the screen on, and run again."
      exit 1
    fi
    i=$((i + 1))
  done
  echo "$dir done"
done

file "$OUT"/en-US/images/phoneScreenshots/*.png
