# GameFold Lite Pixel UI — Asset Catalog

This catalog covers the first-pass, code-native pixel asset set. The assets use only Android VectorDrawable, ShapeDrawable, and LayerDrawable XML, so they remain density-independent and add no bitmap decoding cost.

## Visual language

- Grid: icon geometry is aligned to whole viewport units; no curves or antialias-dependent detail.
- Silhouette: dark hard outline with one flat fill and, where useful, one highlight.
- Corners: square or stepped. Do not apply rounded clipping to these assets.
- Shadow: a fixed 4dp down-right hard shadow is used on status panels.
- Motion: loading motion should be implemented by swapping or translating whole pixel blocks, not by rotating a smooth Material spinner.
- Safety: this set derives only the general 16-bit moss/stone visual language from the reference. It does not reproduce its scenery, characters, logos, or watermarks.

## Shared palette

| Role | Value |
| --- | --- |
| Deep background | `#182820` |
| Moss | `#78B85A` |
| Light moss | `#A8D878` |
| Slate | `#A9C2CF` |
| Dark slate | `#496777` |
| Hard edge | `#233B42` |
| Bright text/highlight | `#EFF7E8` |
| Dark text | `#17272B` |
| Focus | `#C4EA72` |
| Danger | `#B94F4F` |
| Warning | `#D2A84A` |

The first pass uses these values directly. During integration they should be moved to the shared pixel color tokens owned by the design-system branch.

## Navigation and management icons

All icons are authored at 24dp. The icon is visual content only: every interactive use must place it inside a touch target of at least 48dp × 48dp and provide a localized content description.

| Resource | Meaning | Primary use |
| --- | --- | --- |
| `pixel_icon_back` | Back | Settings and management top bars |
| `pixel_icon_settings` | Settings | Home settings entry |
| `pixel_icon_folder` | Game folder | Empty library and folder manager |
| `pixel_icon_driver` | GPU/driver chip | Driver manager and driver status |
| `pixel_icon_install` | Import/install content | Firmware, keys, DLC, update installation |
| `pixel_icon_user` | User profile | Profile manager |
| `pixel_icon_info` | Information | About, system information, help |

## Status icons and panels

| Resource | Meaning | Notes |
| --- | --- | --- |
| `pixel_icon_warning` | Warning | Amber status; not a destructive action by itself |
| `pixel_icon_success` | Success | Completed import/install/scan |
| `pixel_icon_error` | Error | Failure or destructive confirmation |
| `pixel_state_warning_panel` | Warning panel background | 4dp hard shadow, 3dp outline |
| `pixel_state_success_panel` | Success panel background | 4dp hard shadow, 3dp outline |
| `pixel_state_error_panel` | Error panel background | 4dp hard shadow, 3dp outline |

Status must never be communicated by color alone. Pair each panel with its matching icon and explicit text.

## Loading and empty states

| Resource | Meaning | Integration guidance |
| --- | --- | --- |
| `pixel_state_loading` | Three filled loading blocks | Use as a static fallback; animate whole blocks only when motion is enabled |
| `pixel_state_empty_library` | Empty cartridge/folder library | Place above “Select game folder”; do not use as a full-screen wallpaper |

For operations longer than 300ms, show the loading state and a short status label. For determinate installs, the UI layer should reuse the same hard-edge frame but drive a segmented progress fill from real progress.

## Textures and splash proposal

| Resource | Meaning | Integration guidance |
| --- | --- | --- |
| `pixel_bg_texture_moss` | Moss pixel field | Low-frequency backdrop for home/empty states; verify at target window size before adoption |
| `pixel_bg_texture_stone` | Slate highlight/shadow strip | Panel or button surface sample, not a global background |
| `pixel_splash_background` | Deep moss/slate splash field | Proposal only; launcher/splash theme is intentionally unchanged in this branch |
| `pixel_splash_mark` | Abstract GF cartridge mark | Proposal only; requires product review before replacing branding |

The XML texture layers are lightweight composition samples. Their fixed inset pattern does not tile. Integration should either constrain them to a known panel size or replace them with a purpose-built seamless 8px/16px texture after checking density scaling.

## Accessibility and scaling

- Interactive containers: minimum 48dp × 48dp even when the visible icon is 24dp.
- Focus: use the shared `#C4EA72` focus outline and keep at least 3:1 non-text contrast.
- Labels: icons in settings and management flows must retain visible text labels.
- Screen reader: every standalone icon button needs a localized content description; decorative state artwork should be marked non-accessible.
- Scaling: keep VectorDrawable scaling proportional. Avoid non-integer scale animation because it softens pixel edges.

## Deliberately excluded

- Virtual controller, face buttons, D-pad, sticks, shoulders, triggers, and touch overlay assets.
- Existing launcher, adaptive icon, notification icon, fragments, themes, and navigation resources.
- Copyrighted game scenery, characters, or logos from the visual reference.

## Manual art still required

Before final visual polish, a designer should provide or approve:

1. A true seamless moss texture tested at phone, unfolded foldable, and tablet widths.
2. Final GameFold wordmark and launcher/adaptive icon artwork.
3. Optional richer empty-library illustration that remains original and copyright-safe.
4. Animation keyframes for loading blocks and focus cursor.
5. Optical review of every 24dp icon at mdpi and xxxhdpi; the current set is structurally complete but intentionally minimal.
