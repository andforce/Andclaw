#!/bin/sh

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

PKG="${ANDCLAW_PACKAGE:-com.andforce.andclaw}"
OWNER_COMPONENT="${ANDCLAW_OWNER_COMPONENT:-$PKG/.DeviceAdminReceiver}"
A11Y_SERVICE="${ANDCLAW_ACCESSIBILITY_SERVICE:-$PKG/com.andforce.andclaw.AgentAccessibilityService}"
DEBUG_RECEIVER="${ANDCLAW_DEBUG_RECEIVER:-$PKG/com.andforce.andclaw.AgentDebugReceiver}"

ARTIFACTS_DIR="${ANDCLAW_ARTIFACTS_DIR:-$ROOT_DIR/out/device-owner}"
RUNS_DIR="${ANDCLAW_RUNS_DIR:-$ARTIFACTS_DIR}"
APK_DEFAULT="${ANDCLAW_DEVICE_OWNER_APK:-$ARTIFACTS_DIR/andclaw-single-owner-signed.apk}"

adb_cmd() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

print_header() {
  printf '\n== %s ==\n' "$1"
}

ensure_artifacts_dir() {
  mkdir -p "$ARTIFACTS_DIR"
}

wait_for_device() {
  adb_cmd wait-for-device
}

wait_for_boot() {
  wait_for_device
  while :; do
    boot="$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [ "$boot" = "1" ]; then
      break
    fi
    sleep 2
  done
  sleep 3
}

grant_if_possible() {
  perm="$1"
  adb_cmd shell pm grant "$PKG" "$perm" >/dev/null 2>&1 || true
}

resolve_apk() {
  if [ -n "${1:-}" ]; then
    printf '%s\n' "$1"
    return 0
  fi

  if [ -f "$APK_DEFAULT" ]; then
    printf '%s\n' "$APK_DEFAULT"
    return 0
  fi

  if [ -d "$ARTIFACTS_DIR" ]; then
    found_apk="$(find "$ARTIFACTS_DIR" -maxdepth 1 -type f -name '*.apk' | sort | head -n 1)"
    if [ -n "$found_apk" ]; then
      printf '%s\n' "$found_apk"
      return 0
    fi
  fi

  echo "No APK found. Put a signed APK at $APK_DEFAULT, pass it as the first argument, or set ANDCLAW_DEVICE_OWNER_APK." >&2
  return 1
}
