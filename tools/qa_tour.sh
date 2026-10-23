#!/usr/bin/env bash
# Screenshot tour of every design state (design-package states[].qaCapture) on emulator-5568.
#   tools/qa_tour.sh                 all states
#   tools/qa_tour.sh S04 S05         only these (names as below; each sets itself up from the base scene)
# Output: $OUT (default ../spatial-app-factory/apps/sky-dome/build/states)/<name>.png + tour.txt (state lines).
# Base scene: Beijing, 2026-10-20 21:30, north calibrated so that the camera at Stage yaw 0 looks south
# ("calibrate 180"), Chinese copy. The camera is driven by tools/cam.py (posted right-button drags).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/../spatial-app-factory/apps/sky-dome/build/states}"
mkdir -p "$OUT"
D="bash $ROOT/tools/dev.sh"
CAM="python -X utf8 -I $ROOT/tools/cam.py"
LOG="$OUT/tour.txt"

cmd() { $D cmd "$1" "${2:-}" >/dev/null; sleep "${3:-0.4}"; }
shot() {
  local name="$1"
  sleep "${SETTLE:-3}"
  SHOTS="$OUT" $D shot "$name" >/dev/null
  $D cmd dump >/dev/null; sleep 0.4
  echo "$name :: $($D log 'DUMP state=' 1 | sed -E 's/.*DUMP state=([^ ]+).*/\1/')" | tee -a "$LOG"
}
relaunch() {
  adb -s emulator-5568 logcat -c
  $D relaunch >/dev/null
  for i in $(seq 1 60); do $D log "SCENE built" 1 | grep -q built && break; sleep 0.5; done
}
base() {
  cmd lang zh; cmd setCity beijing; cmd setTime 2026-10-20T21:30; cmd calibrate 180
  cmd putback; cmd popup close; cmd bodyclose; cmd fast off; cmd aim off; cmd dark off
  for l in lines bodies compass; do cmd layer "$l on"; done
  $CAM reset >/dev/null
}

S00() { adb -s emulator-5568 logcat -c; $D relaunch >/dev/null; sleep 0.3; SETTLE=0 shot S00; }
S01() { relaunch; cmd lang zh; sleep 3; shot S01; }
S02() { base; shot S02_south_30; $CAM look 0 30 >/dev/null; shot S02_south_up30; $CAM look 0 85 >/dev/null; shot S02_zenith; $CAM look 0 -35 >/dev/null; shot S02_down_room_only; $CAM reset >/dev/null; }
M1() { relaunch; cmd lang zh; cmd setCity beijing; cmd setTime 2026-10-20T21:30; sleep 2; cmd skip; cmd toast demo; shot S02+M1_toast; cmd toast none; shot S02+M1; }
M2() { base; cmd dial "3"; sleep 2.5; shot S02+M2; }
M3() { base; cmd fast on; sleep 3; shot S02+M3; cmd fast off; }
M4() { base; cmd setTime 2026-10-20T14:00; shot S02+M4; }
M5() { base; $CAM look 0 30 >/dev/null; cmd layer "lines off"; shot S02+M5_lines_off; cmd layer "lines on"; $CAM reset >/dev/null; }
M6() { base; cmd dark on; sleep 8; shot S02+M6; cmd dark off; sleep 8; shot S02_after_dark_off; }
M7() { base; cmd recenter; $CAM look 180 0 >/dev/null; shot S02+M7; $CAM reset >/dev/null; cmd calibrate 180; }
S03() {
  base; cmd setTime 2027-01-15T21:00; sleep 1
  local w; w="$($D cmd where Ori >/dev/null; sleep 0.5; $D log 'CMD where' 1)"
  local alt az; alt=$(echo "$w" | sed -E 's/.* alt=(-?[0-9.]+).*/\1/'); az=$(echo "$w" | sed -E 's/.* az=(-?[0-9.]+).*/\1/')
  # "calibrate 180": azimuth A is (A - 180)° to the right of Stage -Z
  ORI_YAW=$(python -I -c "import sys;print(round(float(sys.argv[1])-180,1))" "$az"); ORI_ALT=$alt
  $CAM look "$ORI_YAW" "$ORI_ALT" >/dev/null
  shot S03_orion
}
S04() { S03; cmd pinch; $CAM look "$(python -I -c "import sys;print(float(sys.argv[1])+12)" "$ORI_YAW")" 0 >/dev/null; SETTLE=4 shot S04; cmd stick "1 1500"; sleep 2.5; shot S04_stick_turned; cmd turn 0; }
S05() { S04; cmd depth on; sleep 1.5; cmd turn 60; shot S05_side60; cmd turn 0; shot S05_front; cmd depth off; cmd putback; }
S06() { base; cmd setTime 2026-10-21T04:00; sleep 1; cmd body jupiter; SETTLE=4 shot S06_jupiter; cmd bodyclose; cmd setTime 2026-10-20T21:30; cmd body moon; SETTLE=4 shot S06_moon; cmd bodyclose; }
S07() { base; $CAM look 25 -30 >/dev/null; cmd dial "3 hold"; sleep 2.5; SETTLE=2 shot S07_dragging; cmd dialrelease; shot S07_released; $CAM reset >/dev/null; }
S08() { base; cmd popup time; shot S08; cmd setCity sydney; cmd popup close; $CAM look 0 30 >/dev/null; shot S08_sydney_south; $CAM reset >/dev/null; cmd setCity beijing; }
S09() { base; cmd popup tonight; shot S09_night; cmd popup close; cmd setTime 2026-10-20T14:00; cmd popup tonight; shot S09_day; cmd popup close; }
S10() {
  base; cmd guidehold on
  # turned away (facing north), then pick the constellation row (the last one): the arrow shows the way
  $CAM look 180 0 >/dev/null
  cmd popup tonight; sleep 2; $D cmd tonight 2 >/dev/null; sleep 0.5
  shot S10_arrow_back_turned
  cmd guideend
  # facing the target, pick it again: arrives at once, the ring pulses (held for the shot)
  local w; w="$($D cmd where PsA >/dev/null; sleep 0.5; $D log 'CMD where' 1)"
  local alt az; alt=$(echo "$w" | sed -E 's/.* alt=(-?[0-9.]+).*/\1/'); az=$(echo "$w" | sed -E 's/.* az=(-?[0-9.]+).*/\1/')
  $CAM look "$(python -I -c "import sys;print(round(float(sys.argv[1])-180,1))" "$az")" "$alt" >/dev/null
  cmd guidehold on; cmd popup tonight; sleep 2; $D cmd tonight 2 >/dev/null; sleep 0.5
  shot S10_arrived
  cmd guideend
  $CAM reset >/dev/null
}
S11() { base; cmd popup settings; shot S11; cmd popup close; cmd lang en; cmd pull Ori; SETTLE=4 shot S11_english_bar_card; cmd putback; cmd lang zh; }
S12() { base; cmd emptypinch; cmd toast nolines; SETTLE=2 shot S12; cmd toast none; }
S13() { base; cmd popup settings; cmd failStage 1; cmd dark on; cmd toast failed; SETTLE=2 shot S13; cmd popup close; }

ALL="S00 S01 S02 M1 M2 M3 M4 M5 M6 M7 S03 S04 S05 S06 S07 S08 S09 S10 S11 S12 S13"
: > "$LOG"
$CAM reset >/dev/null 2>&1
for s in ${*:-$ALL}; do echo "== $s"; $s; done
echo "done -> $OUT"
