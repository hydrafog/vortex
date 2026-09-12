# Components

Android shared primitives live in `Common.kt`, device cards in `PeerDeviceCard.kt`, approval UI in `SasApprovalDialog.kt`, notes in `NoteCarousel.kt` with `NoteStore`, navigation in `VortexRoot.kt`, and branding in `VortexLogo.kt`.

## Primitive contracts

| Component | Source | Contract |
| :-------- | :----- | :------- |
| `AppHeader` | `Common.kt` | Unified top branding header carrying the `36.dp` `VortexLogo` on Hub, `titleMedium` title, and optional `bodySmall` tagline |
| `CardHeader` | `Common.kt` | `42.dp` duotone icon tile with `12.dp` rounding, `22.dp` glyph, tinted `primaryContainer` background |
| `StatusDot` | `Common.kt` | Retired presence dot |
| `BatteryRow` | `Common.kt` | Green battery icon plus percentage in `bodySmall` |
| `SurfaceCard` | `Common.kt` | Full-width surface container, `12.dp` rounding, `20.dp` padding, pillow card shading |
| `pillowCard` | `Common.kt` | Shader-free 3D pillow shading modifier applying mathematical Color Dodge top-left highlight and multiplicative bottom-right shadow directly to the surface color, finished with a `1.dp` `outlineVariant` border |
| `VortexDivider` | `Common.kt` | Full-width quiet fill step |
| `PairedRow` | `Common.kt` | Paired-device row on `surfaceContainerLow` with solid fill, `36.dp` icon tile, name in `bodyMedium`, short identifier in `bodySmall` |
| `HintCard` | `Common.kt` | Advisory card with message text in `tertiary`, a primary action button, and an optional dismiss action in a quiet tone |
| `ThisDeviceCard` | `ThisDeviceCard.kt` | Full-width device card placed above the endpoints section with pillow card shading; displays device name, `THIS DEVICE` tag, and resolved Wi-Fi IP |
| `PeerDeviceCard` | `PeerDeviceCard.kt` | Full-width device card with `CardCorner` (`16.dp`), pillow card shading, `16.dp` padding, `16.dp` inner padding; `CardHeader` with name and connection status, battery row, distro and IP lines on the This device card, and a `Quick Actions` group |
| `EarbudsCard` | `EarbudsCard.kt` | Companion card with `CardCorner` (`16.dp`), pillow card shading, `16.dp` padding, top-aligned `CardHeader`, device name, battery, plus `Quick Actions` (audio route, picker, remove) |
| `SasApprovalDialog` | `SasApprovalDialog.kt` | Approval dialog on the surface container with the title from `str("sas.title")`, emoji comparison content, and approve and reject actions |
| `VortexLogo` | `VortexLogo.kt` | Brand mark at `36.dp` default size, tinted with the scheme `primary` token |
| `NoteCarousel` | `NoteCarousel.kt` | Horizontal scrollable row of square note cards with pillow card shading in recency order, leftmost circular add-note button, `12.dp` item spacing with `16.dp` peek |

## Pillow card surface treatment

App cards across Vortex share the shader-free pillow surface treatment provided by `Modifier.pillowCard`. The modifier applies:
1. Directional illumination via a diagonal gradient matching `pillow.glsl` directly on the card's theme surface color.
2. Top-left highlight derived via the exact Color Dodge formula from `pillow.glsl`, providing an organic lightness lift (+7 to +9 RGB units in dark mode) without washing out into white.
3. Bottom-right ambient shadow derived via multiplicative darkening from `pillow.glsl`, providing subtle depth (-4 to -6 RGB units in dark mode) without crushing into black.
4. Clean neutral preservation: the center and anti-diagonal corners (top-right and bottom-left) remain 100% the authentic, unaltered base theme color.
5. `1.dp` outline stroke: strokes a crisp perimeter outline using `MaterialTheme.colorScheme.outlineVariant` (`#242429` Dark, `#1E1E22` OLED, `#E7E7EB` Light), giving cards clean physical separation from background canvases.


## Card info

Peer card info stacks vertically: `CardHeader`, name and connection status, distro and IP lines on the This device card, battery row, then a `Quick Actions` group collecting action icons (view screen, lock toggle, shutdown) in uniform `36.dp` tiles with `20.dp` glyphs. Cards without active host state drop the distro line.

## Section header, endpoint cards, and calendar

The `This device` card sits directly above the `PAIRED ENDPOINTS & DEVICES` section header, displaying local IP information on a full-width surface. The section header reads `PAIRED ENDPOINTS & DEVICES` with live counts pinned right in the form `1 Active · 2 total`. Below the header sit the two endpoint cards side by side with an aspect ratio of `1.19f` (width/height). The `CALENDAR` section header follows the endpoints with `CalendarCard`.

## See also

- `design-system.md` overviews the design principles and file map.
- `spacing-layout.md` records the grid behind card placement.
- `notes.md` records the notes carousel in depth.
- `iconography.md` records the duotone treatment behind card glyphs.
- `states.md` records the `Connected` and `Not Connected` appearances.
