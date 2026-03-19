#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

print_header "Wait For Boot"
wait_for_boot

print_header "Accessibility"
adb_cmd shell settings put secure enabled_accessibility_services "$A11Y_SERVICE"
adb_cmd shell settings put secure accessibility_enabled 1

print_header "AppOps"
adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb_cmd shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb_cmd shell cmd appops set "$PKG" GET_USAGE_STATS allow || true

print_header "Runtime Permissions"
grant_if_possible android.permission.RECORD_AUDIO
grant_if_possible android.permission.CAMERA
grant_if_possible android.permission.ACCESS_FINE_LOCATION
grant_if_possible android.permission.ACCESS_COARSE_LOCATION
grant_if_possible android.permission.READ_PHONE_STATE
grant_if_possible android.permission.READ_SMS
grant_if_possible android.permission.READ_EXTERNAL_STORAGE
grant_if_possible android.permission.WRITE_EXTERNAL_STORAGE
grant_if_possible android.permission.GET_ACCOUNTS

print_header "Launch App"
adb_cmd shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
