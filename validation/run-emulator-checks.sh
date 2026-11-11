#!/usr/bin/env bash
set -euo pipefail
amaya_test_package="${AMAYA_VALIDATION_PACKAGE:-com.amaya.intelligence.debug}"
mkdir -p validation/runtime
collect_evidence() {
  mkdir -p validation/runtime/locale-validation
  timeout 15s adb exec-out run-as ${amaya_test_package} tar -C files/locale-validation -cf - . |
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
  adb shell pm grant ${amaya_test_package} android.permission.POST_NOTIFICATIONS
fi
adb shell appops set ${amaya_test_package} MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/Download/Amaya-l10n
adb logcat -v threadtime > validation/runtime/logcat.txt &
logcat_pid=$!
(while :; do collect_evidence 2>/dev/null || true; sleep 8; done) &
collector_pid=$!
run_test() {
  timeout 20s adb shell am force-stop ${amaya_test_package}
  timeout 300s adb shell am instrument -w -e class "com.amaya.intelligence.localization.LocalizationSmokeTest#$1" \
    ${amaya_test_package}.test/androidx.test.runner.AndroidJUnitRunner | tee "validation/runtime/$1.log"
  if ! grep -q 'OK (1 test)' "validation/runtime/$1.log"; then
    return 1
  fi
}
run_test chineseCoreFlow
amaya_tests_failed=0
run_test pickedPhotosAndFilesAreVisible || amaya_tests_failed=1
run_test projectConversationCanBeRenamed || amaya_tests_failed=1
run_test renamedConversationSurvivesRestart || amaya_tests_failed=1
if [ "${AMAYA_CAPABILITY_ONLY:-false}" = "true" ]; then
  run_test refreshedImageCapabilityReachesProvider || amaya_tests_failed=1
  collect_evidence
  exit "$amaya_tests_failed"
fi
run_test chinesePersistsThenEnglish || amaya_tests_failed=1
run_test englishPersistsThenSystem || amaya_tests_failed=1
set_system_locale() {
  adb root
  adb shell setprop persist.sys.locale "$1"
  adb reboot
  timeout 90s adb wait-for-device
  for attempt in $(seq 1 90); do
    if timeout 15s adb shell getprop sys.boot_completed | tr -d '\r' | grep -q '^1$'; then break; fi
    sleep 2
  done
}
set_system_locale zh-CN
run_test followsChineseSystem || amaya_tests_failed=1
set_system_locale fr-FR
run_test unsupportedSystemFallsBackToEnglish || amaya_tests_failed=1
adb shell settings put system font_scale 1.3
run_test largeFontLanguageDialog || amaya_tests_failed=1
adb shell settings put system font_scale 1.0
run_test refreshedImageCapabilityReachesProvider || amaya_tests_failed=1
collect_evidence
exit "$amaya_tests_failed"
