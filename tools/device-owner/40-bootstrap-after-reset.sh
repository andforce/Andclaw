#!/bin/sh
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$DIR/common.sh"

APK="$(resolve_apk "${1:-}")"

"$DIR/00-diagnose-reset-state.sh" || true
if ! "$DIR/10-provision-single-owner.sh" "$APK"; then
  "$DIR/00-diagnose-reset-state.sh" || true
  exit 1
fi
"$DIR/20-repair-single-owner-runtime.sh"
"$DIR/30-verify-single-owner.sh"
