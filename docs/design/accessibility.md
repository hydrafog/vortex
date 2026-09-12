# Accessibility

Vortex follows Android accessibility guidelines with explicit rules for touch targets, dynamic type, screen readers, contrast, and reduced motion.

## Touch targets

Visual tiles and buttons may be smaller than the Android 48dp minimum. Hit targets must be at least `48dp` regardless of visual size.

| Element | Visual size | Hit target |
| :--- | :--- | :--- |
| Quick action | `36.dp` | `48.dp` |
| Notification button | `32.dp` | `48.dp` |
| Navbar destination | `22.dp` glyph | `48.dp` |
| Settings row | `36.dp` icon tile | `48.dp` |
| Calendar day | Variable | `48.dp` |

The extra touch area extends beyond the visual element without affecting layout.

## Dynamic type

The system must remain usable at 200% font scale. Text truncation, max lines, and scroll behavior are defined per role.

- Body text (`Satoshi`, `15.sp`) truncates at 2 lines with ellipsis.
- Caption text (`Satoshi`, `13.sp`) truncates at 1 line.
- Note snippets (`bodySmall`, `13.sp`) truncate at 2 lines.
- Settings hints (`bodySmall`, `12.sp`) truncate at 1 line.
- Label eyebrows (`Satoshi`, `12.sp`, uppercase) wrap to 2 lines if needed.

The `132.dp` note square must accommodate 2-line snippets at 200% scale without clipping. If space is insufficient, the snippet truncates and the title remains visible.

## Screen readers

All interactive and status elements require content descriptions with appropriate roles and state announcements.

| Element | Content description | Role | State announcement |
| :--- | :--- | :--- | :--- |
| Duotone icon glyphs | Descriptive label from action name | `ImageButton` or `ImageView` | None |
| Battery indicator | Battery percentage | `ProgressBar` | Current percentage |
| Quick actions | Action name (view screen, lock, suspend, shutdown) | `ImageButton` | Current state (on/off) |
| Notification actions | Action name (open, toggle, send) | `ImageButton` | None |
| Widget toggles | Toggle name | `Switch` | Checked/unchecked |
| Status indicator | Connection state | `TextView` | `Connected` or `Not Connected` |
| Calendar day | Day number with events count | `Button` | Number of events |

## Contrast

All foreground/background pairs must meet WCAG AA (4.5:1 for normal text, 3:1 for large text). Mode-specific tokens are defined in `color.md`.

| Pair | Contrast ratio (Dark) | Contrast ratio (Light) | AA |
| :--- | :--- | :--- | :--- |
| `onBackground` / `background` | 15.5:1 | 16.5:1 | Pass |
| `onSurface` / `surface` | 15.5:1 | 16.5:1 | Pass |
| `onSurfaceVariant` / `surface` | 4.6:1 | 4.6:1 | Pass |
| `onPrimaryContainer` / `primaryContainer` | Computed by `onAccentFor` | Computed by `onAccentFor` | Pass |
| `error` / `background` | 4.6:1 | 4.6:1 | Pass |
| `tertiary` / `background` | 3.2:1 | 3.1:1 | Large text only |

The `error` token at `#EF4444` passes AA on all backgrounds. The `tertiary` amber at `#D97706` on light `#FAFAFA` is borderline; it is restricted to warning surfaces with bold or large text, or paired with `tertiary` on `background` at sufficient weight.

## Reduced motion

Reduced-motion preferences disable all animation with instant state changes. This applies to:

- Scale (`0.99` press feedback)
- Slide (screen transitions, navbar indicator)
- Stagger (card entry offsets)
- Settle (carousel snap)
- Shimmer (loading states render statically)
- Indicator animation (navbar pill transition)

All animation durations collapse to `0ms` when reduced motion is enabled.

## RTL and localization

The app supports English, Uzbek, and Russian. RTL layouts mirror horizontally: navbar destinations, quick actions, and calendar week strips all reverse. Longer strings in segmented controls must not clip; labels use `12.sp` semibold with sufficient padding and wrap support.

## Focus order

Focus follows visual order for each screen:

- Hub: header, hint cards, this device card, endpoint cards, calendar, notes carousel, pair-new card.
- Settings: category cards top to bottom, rows within each card left to right.
- Notifications: laptop chip, audio chip, lock group, clipboard group (left to right).
- Carousel: add-new-note square first, then existing notes in recency order.

Tabbed navigation between destinations follows the navbar order: Hub, Files, Settings.

## See also

- `color.md` defines contrast-safe tokens and the `onAccentFor` rule.
- `motion.md` defines duration and easing scales with reduced-motion enforcement.
- `states.md` defines appearance across all connection, data, and control states.
- `design-system.md` defines the visual principles.
