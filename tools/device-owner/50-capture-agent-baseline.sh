#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

STAMP="$(date '+%Y%m%dT%H%M%S')"
OUT_DIR="$RUNS_DIR/baseline-$STAMP"
PROMPT="${1:-开始录屏；打开浏览器并停留在首页；返回桌面；打开设置查看电池页面并截图；返回桌面；停止录屏并结束任务}"

mkdir -p "$OUT_DIR"

print_header "Create Output Directory"
echo "$OUT_DIR"

print_header "Baseline State"
"$DIR/00-diagnose-reset-state.sh" | tee "$OUT_DIR/device-state.txt"

print_header "Record Existing Artifacts"
BEFORE_SCREENSHOT="$(adb_cmd shell ls -t /sdcard/Pictures/Andclaw 2>/dev/null | head -n 1 | tr -d '\r' || true)"
BEFORE_VIDEO="$(adb_cmd shell ls -t /sdcard/Movies/Andclaw 2>/dev/null | head -n 1 | tr -d '\r' || true)"
printf 'before_screenshot=%s\nbefore_video=%s\n' "$BEFORE_SCREENSHOT" "$BEFORE_VIDEO" | tee "$OUT_DIR/before-artifacts.txt"

print_header "Start Agent Benchmark"
adb_cmd logcat -c
adb_cmd shell am broadcast \
  -a com.andforce.andclaw.action.START_AGENT \
  -n "$DEBUG_RECEIVER" \
  --es prompt "$PROMPT" >/dev/null

DONE=0
COUNT=0
while [ "$COUNT" -lt 48 ]; do
  sleep 5
  COUNT=$((COUNT + 1))
  LOG_OUT="$(adb_cmd logcat -d -s AgentDebugReceiver:D AgentController:D AiAccessibility:D)"
  printf '%s\n' "$LOG_OUT" > "$OUT_DIR/agent-logcat.txt"
  if printf '%s\n' "$LOG_OUT" | grep -q '\[system\]: Finished\.'; then
    DONE=1
    break
  fi
done

if [ "$DONE" -ne 1 ]; then
  echo "Benchmark agent did not finish within timeout" >&2
  exit 1
fi

print_header "Collect New Artifacts"
AFTER_SCREENSHOT="$(adb_cmd shell ls -t /sdcard/Pictures/Andclaw 2>/dev/null | head -n 1 | tr -d '\r' || true)"
AFTER_VIDEO="$(adb_cmd shell ls -t /sdcard/Movies/Andclaw 2>/dev/null | head -n 1 | tr -d '\r' || true)"
printf 'after_screenshot=%s\nafter_video=%s\n' "$AFTER_SCREENSHOT" "$AFTER_VIDEO" | tee "$OUT_DIR/after-artifacts.txt"

if [ -n "$AFTER_SCREENSHOT" ] && [ "$AFTER_SCREENSHOT" != "$BEFORE_SCREENSHOT" ]; then
  adb_cmd pull "/sdcard/Pictures/Andclaw/$AFTER_SCREENSHOT" "$OUT_DIR/$AFTER_SCREENSHOT" >/dev/null
fi

if [ -n "$AFTER_VIDEO" ] && [ "$AFTER_VIDEO" != "$BEFORE_VIDEO" ]; then
  adb_cmd pull "/sdcard/Movies/Andclaw/$AFTER_VIDEO" "$OUT_DIR/$AFTER_VIDEO" >/dev/null
  if command -v ffprobe >/dev/null 2>&1; then
    ffprobe -v error -show_entries format=duration,size -of default=noprint_wrappers=1 "$OUT_DIR/$AFTER_VIDEO" \
      > "$OUT_DIR/video-metadata.txt" || true
  fi
fi

print_header "Capture Final Activity"
adb_cmd shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' \
  > "$OUT_DIR/final-activity.txt" || true

print_header "Summary"
{
  echo "out_dir=$OUT_DIR"
  echo "prompt=$PROMPT"
  echo "agent_finished=yes"
  echo "screenshot=$AFTER_SCREENSHOT"
  echo "video=$AFTER_VIDEO"
} | tee "$OUT_DIR/summary.txt"
