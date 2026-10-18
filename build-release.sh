cd /workspaces/voice-chat-room-real-time
####!/usr/b#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="$HOME/android-build"
mkdir -p "$TOOLS"
if [ -z "${AGORA_APP_ID:-}" ]; then read -r -p "Tempel Agora App ID: " AGORA_APP_ID; fi
export AGORA_APP_ID
export AGORA_TOKEN_URL="${AGORA_TOKEN_URL:-https://rezo-agora-token.bangexx01.workers.dev}"
if [ ! -x "$TOOLS/gradle-8.9/bin/gradle" ]; then
  curl -fsSL -o "$TOOLS/gradle.zip" https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q -o "$TOOLS/gradle.zip" -d "$TOOLS"
fi
export ANDROID_HOME="$TOOLS/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o "$TOOLS/cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o "$TOOLS/cmdtools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platform-tools" "platforms;android-35" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$TOOLS/gradle-8.9/bin/gradle" assembleRelease --no-daemon --stacktrace
mkdir -p apk
cp app/build/outputs/apk/release/app-release.apk apk/REZO-CHAT-VOICE.apk
echo "SELESAI: $PWD/apk/REZO-CHAT-VOICE.apk"
