#!/bin/sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -f local.properties ]; then
  echo "local.properties already exists: $ROOT/local.properties"
  exit 0
fi

SDK_DIR=""
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  SDK_DIR="$ANDROID_SDK_ROOT"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  SDK_DIR="$ANDROID_HOME"
elif [ -d "/Android" ]; then
  SDK_DIR="/Android"
fi

if [ -n "$SDK_DIR" ]; then
  echo "sdk.dir=$SDK_DIR" > local.properties
  echo "Created local.properties with sdk.dir=$SDK_DIR"
  exit 0
fi

echo "No Android SDK detected."
echo "Create local.properties manually with:"
echo "  echo 'sdk.dir=/path/to/Android/Sdk' > local.properties"
exit 1
