# Vortex App Design System

The Vortex design system defines the visual language of the Android app: color tokens, accent policy, typography, spacing, iconography, motion, states, navigation, and the shared primitives that compose every screen. The app renders solid opaque colors with depth expressed through lightness steps. The system is flat. The ten-accent model lives in the Android `AccentColor` enum, with the Linux host treated as a paired endpoint. A third OLED canvas extends the dark mode family with a pure-black base.

This document is the entry point. Each section below summarizes one area and links to the dedicated file with the full in-depth specification. The [README](../../README.md) serves as the single design entry point.

## Design principles

Six principles govern every visual decision in the system.

1. Solid opaque surfaces with tactile pillow shading and perimeter outlines. Cards, containers, and scrims use flat base fills enhanced with shader-free pillow shading and `1.dp` `outlineVariant` borders (`Modifier.pillowCard`). The Android theme derives tinted containers with an opaque `softContainer` lerp toward the canvas.
2. Clear surface separation. Separation comes from solid lightness steps between canvas, card, and quiet fills, accented by subtle directional pillow bevels and perimeter outlines.
3. Horizontal-only cards. Device cards are wider than tall with an aspect ratio of `1.19f` (width/height).
4. Android-first with OLED as a third canvas. The Material3 ladder in `Theme.kt` is the token source of truth across dark, light, and pure-black `000000` OLED canvases with recalibrated foregrounds.
5. System-driven accent. The accent resolves from the device dynamic color on Android S+ with `BrandEmerald` (`2ECC71`) as the fallback. The accent presets in `color.md` are developer references, not user-facing controls; settings exposes no color picker.
6. App chrome through the navbar. Navigation lives in the bottom navbar across Hub, Files, and Settings; the header carries branding only.

## Architecture

The system lives in three locations, each with one responsibility.

| Location | Responsibility |
| :------- | :------------- |
| `android/app/src/main/java/com/vortex/a3/ui/Theme.kt` | Material3 color ladder for dark, light, and OLED schemes, opaque `softContainer` derivation, `onAccentFor` luminance rule, dynamic-color branch with exception fallback |
| `android/app/src/main/java/com/vortex/a3/ui/AccentColor.kt` | Ten-accent enum with codes, labels, and hex values plus display-color resolution |
| `android/app/src/main/java/com/vortex/a3/ui/components/Common.kt` | Shared Compose primitives (`CardHeader`, `BatteryRow`, `SurfaceCard`, `PairedRow`, `HintCard`, `pillowCard`) with flat fills, tactile cushion shading, perimeter outlines, and canonical sizes |

Supporting sources include `android/app/src/main/java/com/vortex/a3/ui/icons/SolarIcons.kt` for the duotone icon set, `android/app/src/main/java/com/vortex/a3/ui/screens/HomeScreen.kt` for the Hub layout, `android/app/src/main/java/com/vortex/a3/ui/components/NoteCarousel.kt` with `NoteStore` for the notes carousel, `android/app/src/main/java/com/vortex/a3/ui/screens/SettingsScreen.kt` for categorized settings, and `android/app/src/main/java/com/vortex/a3/ui/VortexRoot.kt` for navbar composition. The Linux host exposes presence, battery, and `wifi_ip` state as a paired endpoint.

## Document map

Each file below holds the in-depth specification for its area. This overview states the principles; the files state the tokens, contracts, and states.

| Document | Coverage |
| :------- | :------- |
| `typography.md` | `Satoshi` body plus `Space Grotesk` titles with the full type scale |
| `color.md` | Android tokens, OLED canvas, accent presets, and the flat invariant |
| `spacing-layout.md` | Spacing scale, corners, screen grid, and card arrangement |
| `components.md` | Card contracts, section header, pair-new card, and `Quick Actions` |
| `notes.md` | Notes carousel geometry, add-new-note square, and carousel states |
| `iconography.md` | Duotone base plus accent rule, size tiers, and the action map |
| `motion.md` | Duration and easing scale with choreography and reduced motion |
| `states.md` | Connection, data, and control states |
| `navigation.md` | Navbar destinations, Hub order, and header contract |
| `settings.md` | Appearance, Audio, Notifications, Sharing, and Device categories |
| `notifications.md` | Four-group geometry, themed icon, and `RemoteViews` bounds |
| `logo.md` | Brand mark, consumers, and flat restyle |
| `widgets.md` | Home-screen widget content and behavior |
| `accessibility.md` | Touch targets, dynamic type, screen readers, contrast, reduced motion |
| `governance.md` | Token lint, screenshot tests, accessibility tests, deprecation policy |

## Enforcement

Design consistency rests on convention today. The `lefthook.yml` pre-commit hooks guard against merge markers, hardcoded secrets, and oversized files, enforce the `600`-line file limit, and run `cargo fmt`, shell syntax checks, protobuf compilation, and JSON parsing, with `cargo clippy`, `cargo test`, and Android unit tests on push.

## See also

- `typography.md` specifies fonts, scale, and fallbacks.
- `color.md` specifies tokens, OLED, accents, and the flat invariant.
- `spacing-layout.md` specifies spacing, corners, and the screen grid.
- `components.md` specifies card contracts and the pair-new card.
- `notes.md` specifies the notes carousel.
- `iconography.md` specifies the duotone icon system.
- `motion.md` specifies durations, easing, and choreography.
- `states.md` specifies connection, data, and control states.
- `navigation.md` specifies the navbar and Hub order.
- `settings.md` specifies the settings categories.
- `notifications.md` specifies the notification geometry and bounds.
- `logo.md` specifies the brand mark and consumers.
- `widgets.md` specifies the home-screen widget.
- `accessibility.md` specifies touch targets, contrast, dynamic type, and screen-reader rules.
- `governance.md` specifies automated enforcement, testing, and deprecation policy.
- `docs/index.md` indexes the documentation families, including the design family.
- `docs/architecture/overview.md` describes system components and layer boundaries outside presentation scope.
- `docs/development/environment.md` details the toolchain, pre-commit hooks, and formatting standards.
- `docs/features/screen-mirroring.md` describes the screencast background for the screenshare action.
- `assets/SOLAR_LICENSE.txt` records brand-mark provenance under CC BY 4.0.
- `android/app/src/main/java/com/vortex/a3/ui/AccentColor.kt` is the accent source of truth.
- `android/app/src/main/java/com/vortex/a3/ui/Theme.kt` is the Android color ladder source of truth.
