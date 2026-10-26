#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
task_test_dir=$(mktemp -d /private/tmp/gamepad-lsfg-tests.XXXXXX)
trap 'rm -rf "$task_test_dir"' EXIT
java_bin="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin"
"$java_bin/javac" -d "$task_test_dir" src/android/app/src/main/java/org/yuzu/yuzu_emu/utils/LosslessMaterialStore.java tests/gamepad_lsfg/LosslessMaterialStoreTest.java
"$java_bin/java" -cp "$task_test_dir" LosslessMaterialStoreTest
c++ -std=c++20 -I src -I .cache/cpm/simpleini/v4.25 tests/gamepad_lsfg/settings_migration_test.cpp -o "$task_test_dir/settings-test"
"$task_test_dir/settings-test"
c++ -std=c++20 -I src tests/gamepad_lsfg/driver_status_test.cpp -o "$task_test_dir/status-test"
"$task_test_dir/status-test"
