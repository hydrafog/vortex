# States

States describe how the app looks before data arrives, when actions run, and when data is missing.

## Connection states

| State | Appearance |
| :--- | :--------- |
| `Connected` | Name and status grouped left, green battery and distro grouped right; `Quick Actions` enabled with duotone glyphs on `PeerDeviceCard` |
| `Not Connected` | Name and status in `onSurfaceVariant`; the battery line hides when unknown; `Quick Actions` disabled on `PeerDeviceCard` |

## Data states

| State | Appearance |
| :--- | :--------- |
| Loading | Static skeleton with title placeholder in the container tone; no shimmer, glow, or pulse |
| Empty notes | Carousel renders solely the `New Note` card |
| Empty endpoints | Hub shows the `This device` card plus the `Pair New Host or Device` card |
| Pairing | `Pair New Host or Device` card shows the scan state in the caption line |
| Missing distro or IP | The line drops; remaining lines keep `12.dp` rhythm |
| Missing battery | The battery line hides; remaining elements keep position |

## Control states

| State | Appearance |
| :--- | :--------- |
| Selected settings | Segmented and chip controls use the `primaryContainer` fill with `onPrimaryContainer` text |
| Selected navbar destination | Duotone glyph in `onPrimaryContainer` inside a `64.dp` by `32.dp` `primaryContainer` pill; label in `onSurface` semibold |
| Unselected navbar destination | Duotone glyph in `onSurfaceVariant` with label in `onSurfaceVariant` medium |
| Disabled action | Icon holds `onSurfaceVariant` at full opacity with duotone accent muted to the base tone |
| Pressed card | Scale `0.99` over `90.ms` ease-out with the flat fill unchanged |

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the card contracts behind each state.
- `notes.md` records the empty and loading carousel appearances.
- `navigation.md` records the selected navbar appearance.
- `settings.md` records the selected settings appearance.
- `motion.md` records the press and stagger behavior.
