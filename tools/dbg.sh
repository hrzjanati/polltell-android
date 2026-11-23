#!/bin/sh
# Sends a debug command to the Tether app (adb-only hook) and prints its answer.
#   tools/dbg.sh state | pair_code | loopback | probe | helper_start | helper_quit | helper_cmd
#   tools/dbg.sh fake_incoming [ring-iphone] [num] | fake_outgoing [ring-iphone] | fake_end | dryrun_on | dryrun_off
#     (test line only; ring-iphone lets the ring / call reach a linked iPhone on purpose)
#   tools/dbg.sh loopback_call [answer|decline|remote|outgoing|selftest|during-sync] | loopback_handover   (test line only)
#   tools/dbg.sh adb_pair <code> | adb_shell "<cmd>"
#   tools/dbg.sh net [data|hotspot|bt on|off] | airplane on|off | share on|off|loopback|noloopback|state
#   tools/dbg.sh drop (close live sessions from the Android side)
#   tools/dbg.sh inject_ms 20..200|off (uplink playback buffer cap for the next calls; default 40)
#   tools/dbg.sh linklog [n] | only none|off | forget_kind <kind> | contact_test | test_contact +1000000000x | star <id> on|off
#   tools/dbg.sh test_sms +10000000001 "text" | test_sms_delete <id|thread:N> | test_call +10000000001 | test_call_delete <id>
#   tools/dbg.sh block +10000000001 on|off | mute <thread> on|off | lists      (test numbers +1000000000x only)
#   tools/dbg.sh ussd_test '*123#' (dry: validates and logs, never sends) | phone_find on|off (rings the A54!) | ringer (state only)
. "$(dirname -- "$0")/lib.sh"; pick_android
cmd=${1:-state}; shift 2>/dev/null || true
extra=""
case "$cmd" in
  adb_pair) extra="--es code $1";;
  adb_shell) extra="--es sh '$1'";;
  fake_incoming) [ "$1" = "ring-iphone" ] && { extra="--es allow iphone"; shift; }; [ -n "$1" ] && extra="$extra --es num $1";;
  fake_outgoing) [ "$1" = "ring-iphone" ] && extra="--es allow iphone";;
  net) [ -n "$1" ] && extra="--es what $1 --es on ${2:-on}";;
  net_datasub) extra="--es sub $1";;
  share) extra="--es on ${1:-state}";;
  sms_read) extra="--es thread $1 --es upTo $2";;
  test_sms) extra="--es num $1 --es body '${2:-Tether test}' --es out ${3:-0}";;
  test_sms_delete|test_call_delete) extra="--es what $1";;
  test_call) extra="--es num $1";;
  block) extra="--es num $1 --es on ${2:-on}";;
  ussd_test) extra="--es code '$1'";;
  phone_find) extra="--es on ${1:-off}";;
  mute) extra="--es thread $1 --es on ${2:-on}";;
  loopback_call) if [ "$1" = "during-sync" ]; then extra="--es sync full"; elif [ -n "$1" ]; then extra="--es mode $1"; fi;;
  only) extra="--es kind ${1:-off}";;
  inject_ms) extra="--es ms ${1:-off}";;
  forget_kind) extra="--es kind $1";;
  linklog) extra="--es n ${1:-120}";;
  airplane) extra="--es on ${1:-on}";;
  test_contact) extra="--es num $1";;
  star) extra="--es id $1 --es on ${2:-on}";;
  test_contact_delete) extra="--es id $1";;
esac
adb shell "am broadcast -n app.tether.android/app.tether.link.DebugReceiver -a app.tether.DEBUG --es cmd $cmd $extra" \
  | sed -n 's/.*data="\(.*\)"$/\1/p'
