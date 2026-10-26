#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
main_root="$repo_root/src/android/app/src/main"
failures=0

check_absent() {
    local label="$1"
    local pattern="$2"
    shift 2
    if rg -n "$pattern" "$@"; then
        printf 'FAIL: %s\n' "$label"
        failures=$((failures + 1))
    else
        printf 'PASS: %s\n' "$label"
    fi
}

if rg -q '<item name="gamefold_brand_mark" type="drawable">@drawable/gamefold_controller_logo</item>' \
    "$main_root/res/values/gamefold_brand_resources.xml"; then
    printf 'PASS: canonical controller logo alias\n'
else
    printf 'FAIL: canonical controller logo alias\n'
    failures=$((failures + 1))
fi

check_absent \
    'no runtime references to the legacy leaf mark' \
    '@drawable/ic_yuzu|R\.drawable\.ic_yuzu' \
    "$main_root"

cutout_hits="$(rg -n 'displayCutout|Type\.displayCutout\(\)' "$main_root" \
    --glob '*.{kt,java,xml}' \
    --glob '!**/overlay/InputOverlay.kt' || true)"
if [[ -n "$cutout_hits" ]]; then
    printf '%s\n' "$cutout_hits"
    printf 'FAIL: cutout offsets remain outside the excluded game-control overlay\n'
    failures=$((failures + 1))
else
    printf 'PASS: no cutout offsets outside the excluded game-control overlay\n'
fi

check_absent \
    'no fitsSystemWindows=true inside the fixed app stage' \
    'fitsSystemWindows="true"' \
    "$main_root/res" \
    --glob '*.xml'

if rg -q '<color name="pixel_action_shadow">#70264F</color>' "$main_root/res/values/pixel_colors.xml" && \
   rg -q '<color name="pixel_action">#FF2F92</color>' "$main_root/res/values/pixel_colors.xml"; then
    printf 'PASS: magenta action face and shadow use distinct tokens\n'
else
    printf 'FAIL: magenta action face and shadow tokens\n'
    failures=$((failures + 1))
fi

if rg -q '<color name="pixel_section_header">#233F32</color>' "$main_root/res/values/pixel_colors.xml" && \
   rg -q '@color/pixel_text_light' "$main_root/res/values/pixel_styles.xml"; then
    printf 'PASS: dark section-header token and light text are present\n'
else
    printf 'FAIL: section-header contrast tokens\n'
    failures=$((failures + 1))
fi

exit "$failures"
