#!/usr/bin/env bash
# Times YouTube start-up per codec on one device with LivePlaybackTest, 3 rounds each:
# the app's own format choice, then AV1, VP9 and H.264 at up to 1080p and 720p.
# Needs the debug app and its test APK from the same build on that device.
# Usage: scripts/time_playback_start.sh <adb serial> <output dir>
# Then: python3 scripts/playback_timing_report.py <output dir>/attempts.jsonl
set -uo pipefail

serial=$1
out=$2
package=io.github.originalrecipe1.unfurlit
all=youtube-big-buck-bunny,youtube-video,youtube-short-link
mkdir -p "$out"

attempts() {
  adb -s "$serial" shell run-as "$package" cat files/live-playback/attempts.jsonl 2>/dev/null
}

run() { # name, extra instrumentation arguments...
  local name=$1
  shift
  adb -s "$serial" logcat -c
  adb -s "$serial" shell am instrument -w -e livePlayback true -e playSeconds 31 "$@" \
    -e class "$package.ui.viewer.LivePlaybackTest" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$out/$name.instrument.txt" 2>&1
  adb -s "$serial" logcat -d -s PlaybackTiming:I LivePlaybackTest:I > "$out/$name.logcat.txt"
  echo "$(date +%T) $name: $(grep -E '^(OK|Tests run)' "$out/$name.instrument.txt" | tr '\n' ' ')"
}

# Earlier attempts on the device stay there; only this run's are copied.
before=$(attempts | wc -l)
for round in 1 2 3; do
  run "r$round-default" -e linkId "$all"
  for codec in av1 vp9 h264; do
    run "r$round-$codec-1080" -e linkId "$all" -e codec "$codec" -e maxHeight 1080
    # The short link only has 240p, so a 720p run would repeat its 1080p one.
    run "r$round-$codec-720" -e linkId youtube-big-buck-bunny,youtube-video -e codec "$codec" -e maxHeight 720
  done
done
attempts | tail -n +"$((before + 1))" > "$out/attempts.jsonl"
echo "Done: $(wc -l < "$out/attempts.jsonl") attempts in $out/attempts.jsonl"
