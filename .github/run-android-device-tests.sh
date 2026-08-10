#!/usr/bin/env bash

set -u

run_device_tests() {
  gradle :trackhub:connectedDebugAndroidTest "$@" --no-daemon --stacktrace
}

if run_device_tests; then
  exit 0
fi

echo "::warning::Android device test failed once; restarting adb and retrying"
adb kill-server || true
adb start-server
adb wait-for-device

# The second attempt is authoritative. Do not hide a deterministic SDK or test
# failure behind an unbounded retry loop.
run_device_tests --rerun-tasks
