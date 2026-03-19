#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

print_header "Wait For Boot"
wait_for_boot

print_header "Owner"
adb_cmd shell dpm list-owners

print_header "Accessibility"
adb_cmd shell dumpsys accessibility | sed -n '1,24p'

print_header "Package"
adb_cmd shell dumpsys package "$PKG" | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'

print_header "Agent Smoke Test"
adb_cmd logcat -c
adb_cmd shell am broadcast \
  -a com.andforce.andclaw.action.START_AGENT \
  -n "$DEBUG_RECEIVER" \
  --es prompt '截一张当前屏幕并结束任务'
sleep 20

LOG_OUT="$(adb_cmd logcat -d -s AgentDebugReceiver:D AgentController:D)"
printf '%s\n' "$LOG_OUT"

if ! printf '%s\n' "$LOG_OUT" | grep -q '\[system\]: Finished\.'; then
  echo "Agent smoke test did not finish cleanly" >&2
  exit 1
fi

print_header "Latest Screenshot"
adb_cmd shell ls -lt /sdcard/Pictures/Andclaw | sed -n '1,4p'
