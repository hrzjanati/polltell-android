#!/bin/bash
# Call-audio check for a new Android phone (macOS: double-click; or run it from a terminal).
# Places a real call FROM the Android phone to a number you choose, runs the helper's probe and selftest while the
# call is up, hangs up, and turns adb TCP mode back on for PolTell. A phone can bridge call audio when the probe
# shows a capture method and an inject method with "ok": true ("intercept" on Android 13+).
# Results: ~/Desktop/polltell-call-audio.txt
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
OUT="$HOME/Desktop/polltell-call-audio.txt"
H='CLASSPATH=$(pm path app.tether.android | cut -d: -f2) app_process / app.tether.helper.HelperMain'
callstate() { adb shell dumpsys telephony.registry | grep -o 'mCallState=[0-9]' | sort -r | head -1 | cut -d= -f2; }
echo "Connect the Android phone with the USB cable (USB debugging on)..."
adb wait-for-usb-device; adb devices -l; echo
read -r -p "Number to call FROM the Android phone (one you can answer): " NUM
NUM=$(echo "$NUM" | tr -cd '0-9+')
[ -n "$NUM" ] || { echo "No number."; exit 1; }
echo "Calling... answer it on the other phone."
adb shell am start -a android.intent.action.CALL -d "tel:$NUM" >/dev/null
for i in $(seq 1 60); do [ "$(callstate)" = "2" ] && break; sleep 1; done
echo "Call state $(callstate). Waiting 12 s for the answer..."; sleep 12
{
  echo "== $(date)"
  echo "== device: $(adb shell getprop ro.product.model) / Android $(adb shell getprop ro.build.version.release) / SDK $(adb shell getprop ro.build.version.sdk) / $(adb shell getprop ro.board.platform)"
  echo "== callState: $(callstate)"
  echo; echo "== PROBE"; adb shell "$H probe"
  echo; echo "== SELFTEST"; adb shell "$H selftest"
} > "$OUT" 2>&1
adb shell input keyevent KEYCODE_ENDCALL
grep -E 'callState|"mode"|"method"|"name"|"ok"|"error"|"note"' "$OUT"
adb -d tcpip 5555
echo "Done. Results: $OUT"
