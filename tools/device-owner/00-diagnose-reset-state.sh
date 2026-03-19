#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

print_header "ADB"
adb_cmd get-state

print_header "Boot / Provisioning"
adb_cmd shell settings get global device_provisioned
adb_cmd shell settings get secure user_setup_complete
adb_cmd shell am get-current-user

print_header "Owners"
adb_cmd shell dpm list-owners || true

print_header "Accounts"
adb_cmd shell dumpsys account | sed -n '1,80p'

print_header "Andclaw Packages"
adb_cmd shell pm list packages | grep "^package:$PKG" || true

print_header "Accessibility"
adb_cmd shell settings get secure enabled_accessibility_services || true
