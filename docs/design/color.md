# Color

Color separates surfaces through solid lightness steps. The system is flat: canvas, card, and quiet fills differ in lightness. The Material3 ladder in `Theme.kt` is the token source of truth across dark, light, and OLED canvases.

## Android tokens

The Compose schemes in `Theme.kt` derive opaque canvas and surface values dynamically tinted by the active accent color through `tintedSurface`. Base lightness steps anchor each role while adopting the accent's hue: light canvas blends with 14% accent tint, cards blend with 18%, and overlaid containers blend with 24%. Dark canvas blends with 14% accent tint, dark cards with 18%, and dark containers with 24%.

| Role | Base dark level | Base light level |
| :--- | :-------------- | :--------------- |
| `background` (canvas) | `141416` (14% tint) | `FFFFFF` (14% tint) |
| `surface` | `1C1C1F` (18% tint) | `EFF0F3` (18% tint) |
| `onBackground` / `onSurface` | `F4F4F5` | `18181B` |
| `surfaceVariant` | `242428` (24% tint) | `C3C3CE` (24% tint) |
| `onSurfaceVariant` | `A1A1AA` | `52525B` |
| `surfaceDim` | `101013` (12% tint) | `E2E3E8` (16% tint) |
| `surfaceBright` | `2E2E34` (22% tint) | `FFFFFF` (10% tint) |
| `surfaceContainerLowest` | `161619` (12% tint) | `FFFFFF` (10% tint) |
| `surfaceContainerLow` | `1A1A1E` (15% tint) | `EFF0F3` (18% tint) |
| `surfaceContainer` | `1C1C1F` (18% tint) | `EFF0F3` (18% tint) |
| `surfaceContainerHigh` | `232329` (22% tint) | `C3C3CE` (24% tint) |
| `surfaceContainerHighest` | `2A2A30` (26% tint) | `B8B8C4` (26% tint) |
| `tertiary` (warning amber) | `FBBF24` | `D97706` |
| `error` | `EF4444` | `EF4444` |
| `batteryGreen` (success) | `33D17A` | `33D17A` |
| `onBatteryGreen` | `1C1B1F` | `FFFFFF` |

The `tertiary` amber token serves warning surfaces such as `HintCard`, which renders its message text in `tertiary`. The `error` token stays constant across modes. Battery text and glyphs render in `batteryGreen` on both modes, with `onBatteryGreen` as the foreground.

## OLED canvas

The OLED canvas is a third theme alongside dark and light. The canvas blends `000000` with 8% of the active accent so pure blacks carry the accent hue while preserving high contrast. Primary text sits near the `F4F4F5` family and secondary text steps lighter than dark-mode `A1A1AA`. Card fills sit in the `101013` to `161619` band above the canvas, tinted with 14% to 20% of the active accent so cards separate from the background through solid lightness steps only. OLED flag failure renders the dark-mode scheme as the fallback canvas, so an unreadable OLED preference never produces an unstyled surface.

## Accent presets

The app exposes eleven accents through the `AccentColor` enum in `AccentColor.kt` with matching codes, labels, and hex values. Vortex Green (`2ECC71`) is the default accent.

| Code | Name | Hex |
| :--- | :--- | :--- |
| `vortex` | Vortex Green | `2ECC71` |
| `system` | System (dynamic color on Android) | Device dynamic primary on Android |
| `blue` | Blue | `3584E4` |
| `teal` | Teal | `21A48C` |
| `green` | Green | `33D17A` |
| `yellow` | Yellow | `F6D32D` |
| `orange` | Orange | `FF7800` |
| `red` | Red | `E01B24` |
| `pink` | Pink | `F04494` |
| `purple` | Purple | `9141AC` |
| `slate` | Slate | `737E8C` |

`buildVortexColorScheme` resolves the Compose primary from the selected `AccentColor`, then derives `onPrimary` through `onAccentFor`, the tinted container through `softContainer`, and all background and surface tokens through `buildScheme` using `tintedSurface`. Selection is automatic: on Android S and above the `System` code resolves through the device dynamic color; every other configuration resolves the stored code with the `BrandEmerald` fallback below. The `softContainer` helper lerps the accent toward the canvas with factor `0.82`, keeping icon tiles and selected pills opaque. The `tintedSurface` helper pulls neutral lightness levels toward the active accent (from 2% to 10%) so that all canvases, cards, and containers share one unified chromatic hue. The `onAccentFor` helper computes WCAG relative luminance of the accent: luminance above `0.35` yields near-black text (`1C1B1F`), luminance at or below `0.35` yields white. The `onPrimaryContainer` token is a high-contrast foreground against `softContainer`, computed by `onAccentFor` for WCAG compliance. It is not the accent itself.

Accent fallbacks are explicit typed boundaries. `AccentColor.fromCode` maps any unknown stored code to `System`. The `System` accent outside the dynamic-color path resolves to `BrandEmerald` (`2ECC71`), so devices below Android S and failed dynamic lookups still render the brand color. Dynamic-color construction on Android S and above is wrapped in an exception handler that falls back to `VortexDarkColors` or `VortexLightColors` for the active mode.

## Semantic tokens

Beyond the surface tokens, the system defines semantic roles for status and feedback:

| Token | Value (Dark) | Value (Light) | Usage |
| :--- | :--- | :--- | :--- |
| `batteryGreen` | `33D17A` | `33D17A` | Battery level, connected status |
| `onBatteryGreen` | `1C1B1F` | `FFFFFF` | Foreground on `batteryGreen` |
| `tertiary` | `FBBF24` | `D97706` | Warnings, hints |
| `error` | `EF4444` | `EF4444` | Error states, overdue items |

## Flat invariant

The system is flat. Depth comes from solid lightness steps only. The `softContainer` helper lerps the accent toward the canvas with factor `0.82`, keeping icon tiles and selected pills opaque.

## See also

- `design-system.md` overviews the design principles and file map.
- `typography.md` records the text colors behind each type role.
- `iconography.md` records the duotone base and accent colors.
- `states.md` records the `Connected` and `Not Connected` appearances.
