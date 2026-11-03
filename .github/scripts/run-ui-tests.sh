#!/usr/bin/env bash
# Runs inside the emulator job of .github/workflows/ui-tests.yml (working directory: android).
# The UI flow tests (app/src/androidTest/.../flows) of one build flavor, with the network off:
# the tests use fake services and must never reach a real one. Reports stay in app/build; failure
# screenshots (written by the tests to /sdcard/ui-test-failures) and the log land in ./ui-out.
set -u
FLAVOR=${FLAVOR:-full}
TASK="connected$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}DebugAndroidTest"
mkdir -p ui-out
# No "isn't responding" dialogs over the app on a slow emulator.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
# Offline: airplane mode (Wi-Fi and mobile data off).
adb shell cmd connectivity airplane-mode enable || adb shell settings put global airplane_mode_on 1 || true
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell rm -rf /sdcard/ui-test-failures || true
adb logcat -G 16M || true
adb logcat -c || true
# ONLY: one class of the flows package (the workflow's "only" input), or all of them.
if [ -n "${ONLY:-}" ]; then FILTER="-Pandroid.testInstrumentationRunnerArguments.class=com.xlollx.songport.flows.${ONLY}"
else FILTER="-Pandroid.testInstrumentationRunnerArguments.package=com.xlollx.songport.flows"; fi
gradle "$TASK" "$FILTER" \
  --no-daemon --stacktrace --continue 2>&1 | tee ui-out/gradle.log
status=${PIPESTATUS[0]}
# Bounded: if the emulator has gone (it happens), adb would wait for the device until the job's
# timeout; better the reports now and a red job in minutes.
timeout 120 adb pull /sdcard/ui-test-failures ui-out/failures || true
timeout 120 adb logcat -d > ui-out/logcat.txt || true
grep -E "UiFlow|AndroidRuntime|FATAL" ui-out/logcat.txt | tail -n 300 > ui-out/logcat-short.txt || true
echo "$status" > ui-out/status
exit 0
