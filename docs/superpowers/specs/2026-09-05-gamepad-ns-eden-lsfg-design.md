# GamePad NS EDEN / LSFG approved design

Approved in the current task on 2026-09-05: preserve current GamePad NS behavior,
upgrade to EDEN's latest published runtime, automatically import the user's
Lossless.dll, default every game to fixed 2x LSFG, and display actual output FPS.

## Baseline and boundaries

The installed APK was freshly pulled and matched the 1.2/10023 archive SHA-256
18fbd5ee76a925ecc10ceb84ca64439d368ad3c399498a69e4caa3e5a399408c.
The user requested that functionality be frozen as V1.3 before migration.
To preserve version identity consistency, first produce 1.3/10024 with only
version metadata changed from 10023; preserve the original 10023 APK. The first
runtime-upgrade APK is 10025. Every subsequently produced APK increments by one.

Package remains com.gamepad.gamepad. Preserve signing identity, non-debuggable
release mode, external root /sdcard/GamePadNS, all UI/layout/assets/geometry,
virtual and physical input, Home/pause/resume, native user identity, per-game
shadercache and saves/runtime, seed-only save import, material-only inventory,
and the current BOTW/TOTK UltraCam 1080p/60 defaults. Other product trees and
other applications are outside the write scope.

## Runtime

Pin EDEN 1dcc5745918761f5a554fec981b4d144034f6201, the Sep 02 published Nightly.
Upgrade native runtime and required dependencies/JNI/Android integration as a
coherent unit. Reapply local native product patches with comparison to v0.2.1
58c1e20ee58efa3900ba616207d460886214480b. Preserve the complete baseline in a
tag and work on an independent upgrade checkout. Do not import unrelated EDEN
UI redesigns. Verify the new runtime with LSFG off before the final 2x mode.

## LSFG

User steering during execution: all games use the existing global T28 driver
8G5-turnip_mrpurple_T28-toasted.adpkg.zip. Migrate old per-game driver overrides
to inherit global T28; do not spend acceptance effort on a separate T30 path.
Preserve all other per-game settings and driver source materials.

Read /sdcard/GamePadNS/system/Lossless.dll (freshly observed, 7521280 bytes).
Import to native user root/lossless/Lossless.dll and use upstream shader cache
preparation. Fingerprint content; use staging and atomic replacement; preserve
the prior usable copy on failure. Rebuild only LSFG cache when this input changes.
The user-supplied DLL stays external and is not committed or embedded in APKs.

Default frame_gen=true, frame_gen_multiplier=2, frame_gen_target_rate=0 for
existing and new games. Only migrate LSFG fields, preserve other per-game values,
and remember subsequent explicit per-game disable/override. Keep minimal LSFG
controls within existing settings. Evaluate support using the actually selected
Vulkan driver (including custom drivers), not just the system libvulkan probe.
Invalid DLL or unsupported GPU must leave normal emulation available and report
a concise reason without a recurring launch blocker.

## Actual output FPS

Keep PixelNumberView and its geometry/style. Add an independent native output
statistic; do not alter average_game_fps, emulation speed or limiter semantics.
Use actual presentation feedback for both original and generated frames. First
probe VK_GOOGLE_display_timing on the actual selected driver; assign present IDs,
read actualPresentTime, exclude zero/duplicate events, and isolate swapchain
generations. Maintain an approximately one-second moving time window, clearing
on pause/background/game change/recreation and invalidating stale samples.
Never substitute game_fps * multiplier, display refresh rate, queue acceptance,
or a copy fence as actual presentation. No adb/root/debuggable requirement at
runtime. If the driver cannot supply usable feedback, report the measurement
gap and investigate an equivalent public path; do not mark real-FPS work done.

## Acceptance

Compare baseline vs new runtime with LSFG off, then off vs 2x at the same scene.
All six installed games receive smoke coverage; BOTW/TOTK receive focused
UltraCam, save/resume, visual, timing and sustained stability checks.
Cross-check HUD's actual samples against independent SurfaceFlinger game-layer
actualPresentTime over matching >=10-second windows (target <=3% difference).
Cover limited generated frames, refresh-rate ceiling, pause/resume, missing DLL,
driver fallback, new/existing games and restart without unnecessary assembly.
Installation and APK hash readback prove artifact identity separately from real
gameplay acceptance. Preserve caches before a version-incompatible rebuild.

## Authorization

The user explicitly approved implementation, build, candidate archiving,
in-place installation and device verification by saying the plan is otherwise
fine and to start. No repeat approval is required for these steps. Remote
publishing and destructive deletion are not part of this task.
