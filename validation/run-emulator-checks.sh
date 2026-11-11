#!/usr/bin/env bash
set -euo pipefail
mkdir -p validation/runtime
collect_evidence() {
  mkdir -p validation/runtime/locale-validation
  timeout 15s adb exec-out run-as com.amaya.intelligence.debug tar -C files/locale-validation -cf - . |
    tar -xf - -C validation/runtime/locale-validation
}
collector_pid=''
logcat_pid=''
finish() {
  if [ -n "$collector_pid" ]; then kill "$collector_pid" 2>/dev/null || true; fi
  collect_evidence || true
  if [ -n "$logcat_pid" ]; then kill "$logcat_pid" 2>/dev/null || true; fi
}
trap finish EXIT
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]; then
  adb shell pm grant com.amaya.intelligence.debug android.permission.POST_NOTIFICATIONS
fi
adb shell appops set com.amaya.intelligence.debug MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/Download/Amaya-l10n
adb logcat -v threadtime > validation/runtime/logcat.txt &
logcat_pid=$!
(while :; do collect_evidence 2>/dev/null || true; sleep 8; done) &
collector_pid=$!
run_test() {
  adb shell am force-stop com.amaya.intelligence.debug
  adb shell am instrument -w -e class "com.amaya.intelligence.localization.LocalizationSmokeTest#$1" \
    com.amaya.intelligence.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "validation/runtime/$1.log"
  if ! grep -q 'OK (1 test)' "validation/runtime/$1.log"; then
    exit 1
  fi
}
run_test chineseCoreFlow
run_test chinesePersistsThenEnglish
run_test englishPersistsThenSystem
set_system_locale() {
  adb root
  adb shell setprop persist.sys.locale "$1"
  adb reboot
  adb wait-for-device
  for attempt in $(seq 1 90); do
    if adb shell getprop sys.boot_completed | tr -d '\r' | grep -q '^1$'; then break; fi
    sleep 2
  done
}
set_system_locale zh-CN
run_test followsChineseSystem
set_system_locale fr-FR
run_test unsupportedSystemFallsBackToEnglish
adb shell settings put system font_scale 1.3
run_test largeFontLanguageDialog
collect_evidence
