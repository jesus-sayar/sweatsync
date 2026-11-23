#!/usr/bin/env bash
# Test SweatSync on the computer: an Android emulator plus a virtual FTMS machine (see tools/ftms_simulator.py).
#
#   tools/emulator.sh setup        once: creates the emulator and the Python environment of the simulator
#   tools/emulator.sh start        boots the emulator (Spanish, Bluetooth on)
#   tools/emulator.sh install      builds the app and installs it with a test profile (skips onboarding)
#   tools/emulator.sh onboarding   installs it without a profile, to see the onboarding screen
#   tools/emulator.sh treadmill    switches a virtual treadmill on (Ctrl+C switches it off)
#   tools/emulator.sh bike         switches a virtual bike on
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")"
ADB="$SDK/platform-tools/adb"
AVD="sweatsync"
IMAGE="system-images;android-34;google_apis;arm64-v8a"
VENV="$HOME/.venvs/sweatsync-sim"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

emulator_serial() { "$ADB" devices | awk '/^emulator-/{print $1; exit}'; }

adb_emu() {
  local serial
  serial="$(emulator_serial)"
  [ -n "$serial" ] || { echo "The emulator is not running: tools/emulator.sh start" >&2; exit 1; }
  "$ADB" -s "$serial" "$@"
}

setup() {
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --install "emulator" "platform-tools" "$IMAGE" >/dev/null
  if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
    # avdmanager may complain about a missing devices.xml in the image and still create the emulator.
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >/dev/null 2>&1 || true
    "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD" || { echo "Could not create the emulator" >&2; exit 1; }
  fi
  [ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q bumble grpcio protobuf
  echo "Ready. Next: tools/emulator.sh start"
}

# The phone may be connected too: wait for the emulator itself, then for Android to finish booting.
wait_for_boot() {
  until [ -n "$(emulator_serial)" ]; do sleep 2; done
  "$ADB" -s "$(emulator_serial)" wait-for-device
  until [ "$(adb_emu shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  until adb_emu shell pm path android >/dev/null 2>&1; do sleep 2; done
}

start() {
  if [ -z "$(emulator_serial)" ]; then
    "$SDK/emulator/emulator" -avd "$AVD" -gpu host >/dev/null 2>&1 &
  fi
  wait_for_boot
  if [ "$(adb_emu shell getprop persist.sys.locale | tr -d '\r')" != "es-ES" ]; then
    adb_emu root >/dev/null
    sleep 2
    adb_emu shell "setprop persist.sys.locale es-ES; setprop ctl.restart zygote"
    sleep 5
    wait_for_boot
  fi
  adb_emu shell cmd bluetooth_manager enable >/dev/null
  echo "Emulator ready."
}

install() {
  local with_profile="$1"
  (cd "$ROOT" && ./gradlew -q assembleDebug)
  adb_emu install -r -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
  adb_emu shell am force-stop com.sweatsync
  # Only the personal data (weight, date of birth) is touched; history and platform sessions are kept.
  local prefs=/data/data/com.sweatsync/shared_prefs/sweatsync.xml
  local current
  current="$(adb_emu exec-out run-as com.sweatsync sh -c "cat $prefs 2>/dev/null" | grep -v -e weight_kg -e birth_ms -e '</map>' || true)"
  # A fresh install has no preferences yet: start a new file.
  [[ "$current" == *"<map>"* ]] || current=$'<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>'
  {
    printf '%s\n' "$current"
    if [ "$with_profile" = yes ]; then
      # 75 kg, born 1 January 1985.
      printf '    <float name="weight_kg" value="75.0" />\n    <long name="birth_ms" value="473385600000" />\n'
    fi
    printf '</map>\n'
  } | adb_emu shell "run-as com.sweatsync sh -c 'mkdir -p shared_prefs && cat > $prefs'"
  adb_emu shell am start -n com.sweatsync/.ui.MainActivity >/dev/null
  echo "Installed and opened."
}

machine() {
  [ -n "$(emulator_serial)" ] || { echo "The emulator is not running: tools/emulator.sh start" >&2; exit 1; }
  exec "$VENV/bin/python" "$ROOT/tools/ftms_simulator.py" "$@"
}

case "${1:-}" in
  setup) setup ;;
  start) start ;;
  install) install yes ;;
  onboarding) install no ;;
  treadmill | bike) machine "$@" ;;
  *) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
