#!/usr/bin/env bash

set -euo pipefail

ACTION="${1:-snapshot}"
LOGFILE="${2:-}"

require_adb() {
  command -v adb >/dev/null 2>&1 || {
    echo "adb not found in PATH" >&2
    exit 1
  }
}

wait_for_device() {
  adb wait-for-device >/dev/null
}

show_service() {
  echo "== service list =="
  adb shell service list | grep camper_navigator || true
}

show_top_activity() {
  echo "== top activity =="
  adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'" || true
}

show_mode() {
  echo "== secure setting camper_navigator_mode =="
  adb shell settings get secure camper_navigator_mode || true
}

show_lum() {
  echo "== LUM file =="
  adb shell cat /data/system/camper_navigator_lum.properties || true
}

snapshot() {
  wait_for_device
  show_service
  show_top_activity
  show_mode
  show_lum
}

snapshot_log() {
  if [[ -z "$LOGFILE" ]]; then
    LOGFILE="adb_snapshot_$(date +%Y%m%d_%H%M%S).txt"
  fi

  {
    snapshot
  } > "$LOGFILE"

  echo "Snapshot written to $LOGFILE"
  cat "$LOGFILE"
}

set_mode() {
  local mode="$1"
  adb shell am broadcast \
    -a com.asiks.camper.navigator.action.SET_MODE \
    --es com.asiks.camper.navigator.extra.MODE "$mode" \
    --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
}

clear_logs() {
  adb logcat -c
  echo "Logs cleared. Start focused logging with:"
  echo "adb logcat -s CamperNavigatorService CarLauncher CarLauncherUtils ActivityTaskManager ActivityManager"
}

reboot_wait() {
  adb reboot
  wait_for_device
  echo "Device is back online."
}

usage() {
  cat <<'EOF'
Usage:
  ./adb_runbook.sh snapshot
  ./adb_runbook.sh snapshot-log [output-file]
  ./adb_runbook.sh logs-clear
  ./adb_runbook.sh set-home
  ./adb_runbook.sh set-fullscreen
  ./adb_runbook.sh reboot-wait
EOF
}

require_adb

case "$ACTION" in
  snapshot)
    snapshot
    ;;
  snapshot-log)
    snapshot_log
    ;;
  logs-clear)
    clear_logs
    ;;
  set-home)
    set_mode HOME
    snapshot
    ;;
  set-fullscreen)
    set_mode FULLSCREEN
    snapshot
    ;;
  reboot-wait)
    reboot_wait
    snapshot
    ;;
  *)
    usage
    exit 1
    ;;
esac
