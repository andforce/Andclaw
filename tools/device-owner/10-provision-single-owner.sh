#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

APK="$(resolve_apk "${1:-}")"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

print_header "Wait For Boot"
wait_for_boot

print_header "Install APK"
if ! install_out="$(adb_cmd install -r -d "$APK" 2>&1)"; then
  printf '%s\n' "$install_out" >&2
  echo "Install failed. On Xiaomi / MIUI, confirm the on-device USB install dialog if it appears." >&2
  exit 1
fi
printf '%s\n' "$install_out"

print_header "Set Device Owner"
if ! owner_out="$(adb_cmd shell dpm set-device-owner "$OWNER_COMPONENT" 2>&1)"; then
  printf '%s\n' "$owner_out" >&2
  echo "set-device-owner failed. The device must be factory reset and have zero accounts. See docs/device-owner/xiaomi-miui.md for vendor-specific workarounds." >&2
  exit 1
fi
printf '%s\n' "$owner_out"

print_header "Owner Result"
adb_cmd shell dpm list-owners
