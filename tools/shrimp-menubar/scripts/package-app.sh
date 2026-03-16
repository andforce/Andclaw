#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="ShrimpMenubar"
BUILD_DIR="$ROOT/.build/arm64-apple-macosx/release"
BIN="$BUILD_DIR/$APP_NAME"
APP_DIR="$ROOT/dist/$APP_NAME.app"
CONTENTS="$APP_DIR/Contents"
MACOS="$CONTENTS/MacOS"
RESOURCES="$CONTENTS/Resources"
PLIST="$CONTENTS/Info.plist"
ZIP="$ROOT/dist/$APP_NAME-macos-arm64.zip"

cd "$ROOT"
swift build -c release
rm -rf "$APP_DIR" "$ZIP"
mkdir -p "$MACOS" "$RESOURCES"
cp "$BIN" "$MACOS/$APP_NAME"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleExecutable</key>
  <string>$APP_NAME</string>
  <key>CFBundleIdentifier</key>
  <string>ai.clawd.shrimpmenubar</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>$APP_NAME</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
PLIST
/usr/bin/zip -qry "$ZIP" "$(basename "$APP_DIR")" -x '*.DS_Store' -x '__MACOSX/*' -x '*/.DS_Store' -j >/dev/null 2>&1 || true
rm -f "$ZIP"
cd "$ROOT/dist"
/usr/bin/zip -qry "$ZIP" "$(basename "$APP_DIR")" -x '*.DS_Store' -x '__MACOSX/*' -x '*/.DS_Store'
shasum -a 256 "$ZIP"
