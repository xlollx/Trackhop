#!/usr/bin/env bash
# Runs inside the emulator job of .github/workflows/screenshots.yml: installs the app and its
# screenshot test, puts the status bar in demo mode, takes the pictures and pulls them to ./shots.
set -u
# FLAVOR (full or play) picks the build: the same test runs on both.
F=${FLAVOR:-full}
APK=app/build/outputs/apk/$F/debug/app-$F-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/$F/debug/app-$F-debug-androidTest.apk
adb install -r "$APK"
adb install -r "$TEST_APK"
adb shell pm grant com.xlollx.songport android.permission.POST_NOTIFICATIONS || true
adb shell cmd uimode night yes || true
# No "isn't responding" dialogs over the pictures: the launcher (the one that stalls on a busy
# emulator) is switched off, and error dialogs are hidden.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
# A tidy status bar: fixed clock, full battery and signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
adb logcat -G 16M || true
adb shell am instrument -w -e class com.xlollx.songport.StoreScreenshots -e locales "${LOCALES:-en,it}" -e review "${REVIEW:-false}" -e carousel "${CAROUSEL:-false}" -e discover "${DISCOVER:-false}" \
  com.xlollx.songport.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.log
adb pull /sdcard/Android/data/com.xlollx.songport/files/shots shots || true
[ "${CAROUSEL:-false}" = "true" ] && { adb pull /sdcard/Android/data/com.xlollx.songport/files/carousel carousel || true; }
[ "${DISCOVER:-false}" = "true" ] && { adb pull /sdcard/Android/data/com.xlollx.songport/files/discover discover || true; }
[ "${REVIEW:-false}" = "true" ] && { adb pull /sdcard/Android/data/com.xlollx.songport/files/review review || true; }
adb logcat -d -s StoreScreenshots:* AndroidRuntime:E TestRunner:* > logcat.txt || true
grep -q "OK (1 test)" instrument.log
