# Current GamePad NS Baseline

## Baseline identity

- Product: `GamePad NS`
- Latest formal baseline: **V1.8 / 10030**
- Application ID: `com.gamepad.gamepad`
- APK Release asset: `GamePad-NS-V1.8-10030.apk`
- SHA-256: `d3e621a2c060401cfa6cae6b96bf25b4293119be296551bb1eaba118e0031fca`
- Frozen source content commit: `0f2249a7026f9ea2e90a6891125b3537a9f53491`
- Git tag: `gamepad-ns-v1.8-10030-baseline`
- User approval: 2026-10-18, “我验证了一下，没啥问题。帮我标个基线吧”.

The exact accepted and previously installed V1.8 candidate is promoted without
rebuilding, re-signing, reinstalling or changing device data. It was built from
`1cf4a2355f39bf59b7bd7f429cae7576d334f5de` plus `source-changes.patch`;
matching source content was committed after the build.

## Preserved behavior and changes

V1.8 changes the whole-device 16:9 logical display override from 2304×1296 to
**3840×2160** (natural portrait **2160×3840**). The same home toggle restores the
physical landscape **2304×1440**. Game windows continue to follow system
dimensions; physical panel resolution, density and internal game render
multipliers are not changed. The 4K controller and HOME drawing/touch geometry
share a 5/3 scale, with controller vertical translation of -120 pixels.
V1.7's 2304×1296 mode and -72-pixel translation, native fullscreen and legacy
1080p geometry remain supported. IPS/IPS32, per-game LSFG, T28 and save paths
remain unchanged.

## Verification record

- Archived Release build passed with 12 Android unit tests: nine display mode,
  scale and coordinate tests plus three existing UltraCam tests.
- Ten production IPS-parser host regressions passed.
- APK signature verified; signer unchanged from V1.7.
- Prior installation on vivo Pad `iPA2693 / 403G8G00BM611V0` read back
  `com.gamepad.gamepad / 1.8 / 10030`; pulled APK matched byte-for-byte.
- Installation preserved all 38 runtime save files. Six of seven configuration
  files were byte-identical; the only global-config difference enables NFS's
  bigfont MOD, which the user explicitly confirmed changing manually. That
  intentional change is retained; all other configuration content is unchanged.
- App-triggered system switch, native-size restore and override persistence
  outside the app were measured. NFS window and SurfaceView were 3840×2160.
  Its menu showed 60 FPS with the 60FPS IPS applied and frame generation off.
- 4K controller visuals were visible and the scaled HOME touch returned to the
  library. User accepted the candidate and requested this latest baseline.
- Device is disconnected at promotion; no new live device readback is claimed.
  Existing install evidence is retained. Long-duration race frame rate, full
  controller and external-TV regressions were not independently measured.

## Recovery and next version

- V1.7/10029 is preserved at `../releases/baselines/GamePad-NS-V1.7/` with
  tag `gamepad-ns-v1.7-10029-baseline`.
- V1.6/10027 and all earlier baseline/candidate archives and tags remain unchanged.
- Next newly built APK must use versionCode **10031**, updating Gradle and
  `GamePadNsProductContract`.
