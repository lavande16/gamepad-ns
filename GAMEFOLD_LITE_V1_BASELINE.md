# GameFold Lite V1 Auto-Assembly Baseline

Frozen: 2026-07-18

This source tree is the approved emulator and Android frontend baseline for the
GameFold automatic-assembly product line before controller-UI integration.

## Identity

- Eden base commit: `58c1e20ee58efa3900ba616207d460886214480b`
- Application ID: `com.switch.fold.baseline.lite`
- Application name: `GameFold Lite`
- Version name: `58c1e20ee5-clean-lite-v1`
- Build variant: `mainlineRelease`
- ABI: `arm64-v8a`

## Frozen behavior

- Lite settings frontend with five normal entries.
- Hidden maintenance mode toggled by tapping the version seven times.
- Existing performance defaults retained.
- Vulkan validation library excluded from the Android package.
- T26 is not bundled; local GPU-driver ZIP import remains available.
- APK self-update code and package-install permission removed.
- English fallback and Simplified Chinese Android resources retained.

## Boundary

Future auto-assembly work must branch from this baseline. Do not silently
replace this restore point with a later scanner, installer, controller UI, or
direct-launch build. Controller UI and foldable-shell integration remain a
separate product line until an explicit merge decision is made.
