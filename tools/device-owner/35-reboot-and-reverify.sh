#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

print_header "Reboot Device"
adb_cmd reboot

print_header "Wait For Reboot"
wait_for_boot

"$DIR/00-diagnose-reset-state.sh"
"$DIR/30-verify-single-owner.sh"
