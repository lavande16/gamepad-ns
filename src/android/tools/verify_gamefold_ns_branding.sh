#!/usr/bin/env bash

set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"

failure_count=0

require_match() {
    pattern=$1
    file=$2
    message=$3
    if ! rg -q -- "$pattern" "$file"; then
        printf 'FAIL: %s\n' "$message" >&2
        failure_count=$((failure_count + 1))
    fi
}

forbid_match() {
    pattern=$1
    file=$2
    message=$3
    if rg -q -- "$pattern" "$file"; then
        printf 'FAIL: %s\n' "$message" >&2
        failure_count=$((failure_count + 1))
    fi
}

require_hash() {
    expected=$1
    file=$2
    message=$3
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
    if [ "$actual" != "$expected" ]; then
        printf 'FAIL: %s\n' "$message" >&2
        failure_count=$((failure_count + 1))
    fi
}

require_match 'appNameBase" to "GameFold NS"' app/build.gradle.kts \
    'mainline manifest label must be GameFold NS'
require_match 'versionName = "3.0"' app/build.gradle.kts \
    'formal GameFold NS 3.0 baseline must report versionName 3.0'
require_match 'val appName = if \(name == "mainline"\) "GameFold NS" else "GameFold"' \
    app/build.gradle.kts 'mainline generated app_name must be GameFold NS'
require_match 'android:icon="@drawable/gamefold_ns_launcher_icon"' \
    app/src/main/AndroidManifest.xml 'manifest icon must use the supplied NS icon'
require_match 'android:roundIcon="@drawable/gamefold_ns_launcher_icon"' \
    app/src/main/AndroidManifest.xml 'manifest roundIcon must use the supplied NS icon'
require_match '<string name="gamefold_home_brand_name" translatable="false">GAMEFOLD NS</string>' \
    app/src/main/res/values/strings.xml 'home brand resource must be GAMEFOLD NS'
require_match "'S' to intArrayOf" \
    app/src/main/java/org/yuzu/yuzu_emu/views/PixelWordmarkView.kt \
    'pixel wordmark font must include the S glyph used by GAMEFOLD NS'

for layout in \
    app/src/main/res/layout/fragment_games.xml \
    app/src/main/res/layout-land/fragment_games.xml
do
    require_match 'android:text="@string/gamefold_home_brand_name"' "$layout" \
        "$layout must render the GameFold NS home brand"
done

require_match 'android:id="@\+id/social_links"' app/src/main/res/layout/fragment_about.xml \
    'about page social icon group must have a stable resource id'
require_match 'android:visibility="visible"' app/src/main/res/layout/fragment_about.xml \
    'about page social icon group must be visible'
require_match 'app:srcCompat="@drawable/about_xiaohongshu"' \
    app/src/main/res/layout/fragment_about.xml 'REDnote icon must remain wired'
require_match 'app:srcCompat="@drawable/about_dribbble"' \
    app/src/main/res/layout/fragment_about.xml 'Dribbble icon must remain wired'
require_match 'buttonXiaohongshu.setOnClickListener \{ openLink\(getString\(R.string.dribbble_link\)\) \}' \
    app/src/main/java/org/yuzu/yuzu_emu/fragments/AboutFragment.kt \
    'REDnote and Dribbble buttons must reuse the Dribbble destination'
require_match 'isDebuggable = false' app/build.gradle.kts \
    'formal release build must keep run-as disabled'
forbid_match 'android:debuggable="true"' app/src/main/AndroidManifest.xml \
    'main manifest must not enable run-as'

require_hash 5293b3a154d9c17dc34ded947f24c9628f2b6feb45d14000d065f07512d71152 \
    app/src/main/res/drawable-nodpi/gamefold_dual_screen_on.webp \
    'enabled external-display icon must match the supplied 130x130 WebP'
require_hash 6925b91c95eacb0ed7f548cdc2cd76537ba65ad05ca047044c8603f2edc0288e \
    app/src/main/res/drawable-nodpi/gamefold_dual_screen_off.webp \
    'disabled external-display icon must match the supplied 130x130 WebP'
require_hash d579c51f55341f9a8ca2184656e22ae42a1c67c6c31108918434c38c0bd9f5f2 \
    app/src/main/res/layout/fragment_games.xml \
    'default layout must remain byte-identical to the 3.0 baseline'
require_hash d579c51f55341f9a8ca2184656e22ae42a1c67c6c31108918434c38c0bd9f5f2 \
    app/src/main/res/layout-land/fragment_games.xml \
    'landscape layout must remain byte-identical to the 3.0 baseline'

icon=app/src/main/res/drawable-nodpi/gamefold_ns_launcher_icon.png
expected_icon_hash=de318d09ed1d502c1abee87cef422b3b97f1d4867cf1dbbe20cf1119bd636fb4
if [ ! -f "$icon" ]; then
    printf 'FAIL: supplied NS launcher icon is missing\n' >&2
    failure_count=$((failure_count + 1))
else
    actual_icon_hash=$(shasum -a 256 "$icon" | awk '{print $1}')
    if [ "$actual_icon_hash" != "$expected_icon_hash" ]; then
        printf 'FAIL: NS launcher icon hash differs from supplied icon-512.png\n' >&2
        failure_count=$((failure_count + 1))
    fi
fi

if [ "$failure_count" -ne 0 ]; then
    printf '%s branding policy check(s) failed.\n' "$failure_count" >&2
    exit 1
fi

printf 'GameFold NS branding policy checks passed.\n'
