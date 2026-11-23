#!/bin/bash
# Builds, installs and opens the app on the connected phone (USB or Wi-Fi). Emulators are ignored:
# use tools/emulator.sh for them.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$ANDROID_HOME/platform-tools/adb"

PHONE="$("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {print $1; exit}')"
if [ -z "$PHONE" ]; then
  echo "No phone connected. Pair it with: $ADB pair IP:PAIRING_PORT  and then  $ADB connect IP:PORT"
  exit 1
fi

./gradlew -q assembleDebug
"$ADB" -s "$PHONE" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$PHONE" shell am start -n com.sweatsync/.ui.MainActivity
