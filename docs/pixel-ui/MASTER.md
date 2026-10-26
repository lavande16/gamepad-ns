# GameFold Lite Pixel UI

## Product thesis

GameFold Lite uses a restrained 16-bit handheld-menu shell: a moss field carries the
workspace, blue-grey slate controls carry actions, hard ink edges create depth, and one
bright green focus color communicates selection. Game artwork remains the visual anchor;
the shell never competes with it.

## Content plan

1. **Primary workspace:** game covers, current game, or current settings content.
2. **Support state:** loading, empty, warning, success, and maintenance context.
3. **Detail:** readable technical labels and values, without decorative chrome.
4. **Primary action:** one focused slate control; dangerous actions are explicitly red.

## Interaction thesis

- Pressed controls move visually into their hard shadow within 80–150 ms; layout bounds
  never change.
- Focus changes instantly through a 4dp bright-green frame, suitable for touch, keyboard,
  TV, and future controller navigation.
- Loading feedback uses discrete filled segments or a clipped hard-edged bar. No elastic,
  parallax, blur, perspective, or ornamental looping motion.

## Semantic palette

| Token | Value | Role |
| --- | --- | --- |
| `pixel_background_deep` | `#182820` | Dark app and modal surround |
| `pixel_background_moss` | `#78B85A` | Primary moss field |
| `pixel_background_moss_light` | `#A8D878` | Sparse highlight areas |
| `pixel_surface_slate` | `#A9C2CF` | Interactive slate face |
| `pixel_surface_slate_dark` | `#496777` | Track, divider, and depth face |
| `pixel_border_ink` | `#233B42` | Universal hard outline |
| `pixel_text_light` | `#EFF7E8` | Text on deep or danger surfaces |
| `pixel_text_dark` | `#17272B` | Text on moss, slate, focus, warning |
| `pixel_text_secondary` | `#33484B` | Small supporting text on slate |
| `pixel_focus` | `#C4EA72` | The only selection/action accent |
| `pixel_danger` | `#B74949` | Destructive action only |
| `pixel_warning` | `#D2A84A` | Warning state only |

The approved danger reference was `#B94F4F`; it is adjusted to `#B74949` so light text
reaches a 4.71:1 contrast ratio. Key verified ratios are: 14.04:1 for light text on deep,
8.30:1 for dark text on slate, 6.47:1 for dark text on moss, and 11.28:1 for dark text on
focus.

Do not introduce neon pink, cyan, purple, gradients, transparency-led hierarchy, or a
second focus accent.

## Typography

- Chinese body and technical content: system `sans-serif` for reliable glyph coverage.
- Labels and titles: system `sans-serif-medium`.
- FPS, percentages, versions, and aligned values: system `monospace` with tabular numbers.
- Maximum two families on a page: the readable system face and monospace numeric face.
- A licensed Chinese pixel face may replace title/label styles later. Candidates such as
  Fusion Pixel, Ark Pixel, and Zpix require license and bundled-file verification first.
- Never fetch fonts at runtime. Long descriptions and legal text remain in the readable
  system face even after a display pixel font is approved.

## Geometry and spacing

- Visual pixel: 2dp.
- Standard outline: 2dp; focus outline: 4dp; hard shadow offset: 4dp.
- Spacing rhythm: 4, 8, 16, 24, 32dp.
- Interactive target: at least 48×48dp with at least 8dp between adjacent targets.
- Button height: 56dp; list item minimum: 64dp.
- Corners are square. Depth comes from top highlight plus bottom-right hard shadow.
- No soft elevation, blur, glow, large rounded rectangle, or floating-pill treatment.

## Public resource interface

### Styles

- `GameFoldPixel.Text`, `.Title`, `.Label`, `.Caption`, `.Numeric`
- `GameFoldPixel.Button`, `.Button.Danger`
- `GameFoldPixel.Panel`, `.DialogPanel`, `.ListItem`

### Backgrounds and state lists

- `pixel_panel_background`
- `pixel_button_background`, `pixel_button_danger_background`
- `pixel_dialog_background`
- `pixel_list_item_background`
- `pixel_progress_horizontal` for `ProgressBar` horizontal progress drawables
- `pixel_switch_track`, `pixel_switch_thumb` for native switch-compatible controls

Use semantic colors and these shared drawables from page layouts. Page code must not copy
the underlying hex values or rebuild component shapes locally.

## Component rules

### Buttons

Use slate for ordinary actions, bright green only as the focus frame, and danger red only
for irreversible actions. Pressed state may appear inset, but its measured bounds stay
stable. Text labels are preferred over icon-only actions.

### Panels and dialogs

Panels group only meaningful interaction. Plain layout and dividers are preferred for
dense settings. Dialogs use a strong scrim and a single slate frame. Put the safe action
first in focus order and visually distinguish destructive confirmation.

### Lists and settings

Rows retain native selected, enabled, and accessibility semantics. Switches use a square
thumb and track; sliders and progress indicators use hard-edged segments. State must not
be communicated by color alone: keep labels, check marks, or values.

## Accessibility and implementation constraints

- Preserve native Android controls and semantic roles where possible.
- Every icon-only action requires a localized content description.
- Primary and secondary text meet WCAG contrast targets on their intended surfaces.
- Respect system insets, display cutouts, font scaling, and screen-reader focus order.
- Do not rasterize text or build routine controls with Canvas.
- All drawable resources are static XML; they add no network, runtime font, blur, shader,
  or continuous-animation cost.

## Scope boundary for the first parallel pass

This foundation adds resources only. It does not modify the games page, emulation page,
navigation behavior, emulator lifecycle, quick settings implementation, or any controller
overlay asset. Page teams consume these interfaces after the foundation is integrated.
