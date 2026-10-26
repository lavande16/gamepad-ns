# GamePad NS EDEN / LSFG Implementation Plan

> For agentic workers: use superpowers:subagent-driven-development for bounded
> implementation tasks, followed by task review and a final integration review.

**Goal:** preserve GamePad NS V1.3 functionality while upgrading EDEN and adding
automatic fixed-2x LSFG plus measured post-generation output FPS.

**Architecture:** retain the GamePad product shell and product-specific native
patches on a pinned EDEN runtime; add LSFG material import and independently
measured presentation statistics through the existing Android/JNI boundary.

**Tech Stack:** Android/Kotlin, JNI/C++20, Vulkan, CMake/CPM, Gradle, JUnit.

**Spec:** ../specs/2026-09-05-gamepad-ns-eden-lsfg-design.md

## Global Constraints

- Package com.gamepad.gamepad; release remains non-debuggable with the existing signature.
- Preserve all current 10023 behavior and UI; V1.3 baseline is metadata-only 1.3/10024.
- First new-runtime APK versionCode 10025; increment exactly one for each produced APK.
- Pin upstream 1dcc5745918761f5a554fec981b4d144034f6201.
- Source DLL /sdcard/GamePadNS/system/Lossless.dll remains external.
- Defaults frame_gen=true, frame_gen_multiplier=2, frame_gen_target_rate=0.
- All games inherit existing global T28 (8G5-turnip_mrpurple_T28-toasted.adpkg.zip); migrate old per-game driver overrides.
- FPS must measure actual presentation, not multiplication or queue submission.
- Preserve user data and other products; no remote publishing.

### Task 1: Freeze V1.3 and isolate upgrade work

- [ ] Verify installed 10023 hash against the archived candidate.
- [ ] Change only Gradle/ProductContract version to 1.3/10024; run release unit tests/build.
- [ ] Inspect badging/signature/native payload; archive APK, hash and BASELINE.json.
- [ ] Install with vivo installer, pull installed APK, compare hash, record exact source commit/tag.
- [ ] Preserve current dirty additions in Git and create an isolated upgrade checkout.

### Task 2: Integrate pinned upstream runtime

- [ ] Fetch the pinned commit into local Git, record upstream/local diff and merge base.
- [ ] Merge runtime, dependencies and necessary Android/JNI changes while retaining product UI.
- [ ] Resolve native save/cache paths, UUID/profile, input/lifecycle and settings compatibility.
- [ ] Preserve ABI/signing/product variants; adapt the build to upstream SDL3 audio and Vulkan.
- [ ] Run unit tests, Kotlin compile and full native build; inspect current product layout diff.
- [ ] Review source changes and fix integration findings before feature work.

### Task 3: LSFG automatic materials and defaults

- [ ] Read upstream LosslessScalingHelper, JNI prepare API and frame-gen settings.
- [ ] Add behavior tests for unchanged/replaced/invalid DLL staging and per-game default migration.
- [ ] Observe failing cases, implement content-fingerprint import and failure-safe preparation.
- [ ] Apply fixed-2x defaults once, preserve explicit later overrides and other settings.
- [ ] Make existing and new games inherit global T28, removing old per-game driver overrides as requested.
- [ ] Add minimal settings controls and actual selected-driver support/error reporting.
- [ ] Run covering unit tests and Kotlin/native compile; review the task diff.

### Task 4: Actual presented-frame FPS

- [ ] Add tests for zero/duplicate/out-of-order timing records, window rollover, stale samples,
      swapchain recreation and pause invalidation; observe failure before implementation.
- [ ] Implement standalone presentation-window statistic, independent of core PerfStats.
- [ ] Probe/enable VK_GOOGLE_display_timing; attach IDs and poll actualPresentTime for all output frames.
- [ ] Expose measured FPS plus validity through JNI; connect existing PixelNumberView without geometry changes.
- [ ] Verify unsupported/stale states never report a multiplied or frozen high FPS.
- [ ] Run native statistic tests and integration build; review the task diff.

### Task 5: Device acceptance and candidate handoff

- [ ] Back up affected app configuration and portable saves/caches before runtime testing.
- [ ] Build/archive/install new-runtime APK using existing signature; pull and byte-compare.
- [ ] Verify LSFG off baseline behavior, then automatic fixed-2x DLL loading.
- [ ] Probe actual driver feedback in the formal non-debuggable package and disconnected operation.
- [ ] Compare FPS with >=10-second SurfaceFlinger actual presentation samples; target <=3% deviation.
- [ ] Smoke all six games; focus BOTW/TOTK UltraCam, saves, input, Home, frame pacing and stability.
- [ ] Complete final integration review, report evidence and unresolved gameplay limitations precisely.

## Execution environment

Build from src/android with JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home,
ANDROID_HOME and ANDROID_SDK_ROOT=/Users/11085429/Library/Android/sdk. Use
./gradlew :app:testMainlineReleaseUnitTest :app:assembleMainlineRelease --no-daemon --console=plain.
ADB path /Users/11085429/Library/Android/sdk/platform-tools/adb; enumerate afresh and use explicit serial.
Installer /Users/11085429/.codex/skills/install-apk-vivo/scripts/install_apk_vivo.py.
