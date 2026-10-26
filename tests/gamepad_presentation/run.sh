#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output_dir=$(mktemp -d /private/tmp/gamepad-presentation.XXXXXX)
trap 'rm -rf "$output_dir"' EXIT
c++ -std=c++20 -Wall -Wextra -Werror -pthread -Isrc tests/gamepad_presentation/window.cpp -o "$output_dir/window"
"$output_dir/window"
