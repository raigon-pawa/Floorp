#!/bin/sh
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#
# Start, stop, restart or check one of the headless test emulators.
#
# Usage: tools/avd.sh start|stop|restart|status|healthy <n>
#
# <n> selects the AVD archwave_android_<n> and its serial: 1 -> emulator-5554,
# 2 -> emulator-5556, 3 -> emulator-5558. "healthy" exits 0 only if the
# emulator answers and its display pipeline is alive (a hung software renderer
# is the usual failure: screenshots and UI dumps then block forever; restart).
#
# Environment: ARCHWAVE_AVD_GPU (default swangle), ARCHWAVE_AVD_MEMORY (MB,
# default 3072), ANDROID_HOME (default ~/Android/Sdk).

set -eu

action=${1:-}
n=${2:-}
case "$n" in 1|2|3) ;; *) echo "usage: $0 start|stop|restart|status|healthy <1|2|3>" >&2; exit 2 ;; esac

sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
adb="$sdk/platform-tools/adb"
avd="archwave_android_$n"
port=$((5552 + 2 * n))
serial="emulator-$port"
log="${TMPDIR:-/tmp}/archwave-avd-$n.log"

pids() { pgrep -f "qemu-system[^ ]* .*-avd $avd( |\$)" || true; }

running() { [ -n "$(pids)" ]; }

healthy() {
    running || return 1
    [ "$(timeout 10 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || return 1
    # A live compositor produces a screenshot within a second or two; a hung one never does.
    [ "$(timeout 25 "$adb" -s "$serial" exec-out screencap -p 2>/dev/null | head -c 4 | tail -c 3)" = "PNG" ]
}

stop() {
    running || return 0
    timeout 10 "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do running || return 0; sleep 1; done
    for pid in $(pids); do kill "$pid" 2>/dev/null || true; done
    sleep 2
    for pid in $(pids); do kill -9 "$pid" 2>/dev/null || true; done
}

start() {
    if running; then echo "$avd is already running ($serial)"; return 0; fi
    setsid nohup "$sdk/emulator/emulator" -avd "$avd" -port "$port" \
        -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu "${ARCHWAVE_AVD_GPU:-swangle}" -memory "${ARCHWAVE_AVD_MEMORY:-3072}" -cores 4 \
        >"$log" 2>&1 </dev/null &
    for _ in $(seq 1 90); do
        if [ "$(timeout 5 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            # Animations off and screen always on, so scripted taps and UI tests are deterministic.
            for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
                "$adb" -s "$serial" shell settings put global "$setting" 0
            done
            "$adb" -s "$serial" shell settings put system screen_off_timeout 1800000
            "$adb" -s "$serial" shell svc power stayon true
            echo "$avd booted ($serial), log: $log"
            return 0
        fi
        sleep 2
    done
    echo "$avd did not boot within 180 s; see $log" >&2
    return 1
}

case "$action" in
    start) start ;;
    stop) stop; echo "$avd stopped" ;;
    restart) stop; start ;;
    status) if healthy; then echo "$avd: healthy ($serial)"; elif running; then echo "$avd: running but unresponsive ($serial)"; else echo "$avd: stopped"; fi ;;
    healthy) healthy ;;
    *) echo "usage: $0 start|stop|restart|status|healthy <1|2|3>" >&2; exit 2 ;;
esac
