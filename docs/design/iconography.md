# Iconography

Interface icons render Solar duotone with two stroke layers: a neutral base line plus an accent line with `strokeLineWidth 1.7f`, round caps, and round joins. Interface glyphs avoid single-tone flat strokes or unmapped secondary colors.

## Duotone layers

Interface icons render with neutral tile backgrounds (`surfaceContainerHigh` or `surfaceContainerHighest`), never accented fills. Dual-tone vector paths are composed of a neutral foundational outline line and a vibrant accent highlight line. The base layer resolves to `onSurfaceVariant` to provide crisp structural geometry, while the accent layer resolves to `primary` (the vibrant accent color) across all themes and containers.

| Layer | Tile / Surface Placement | Color Token |
| :--- | :----------------------- | :---------- |
| Base (outline geometry) | Neutral tile (`surfaceContainerHigh`/`Highest`) | `onSurfaceVariant` |
| Accent (action / highlight path) | Neutral tile (`surfaceContainerHigh`/`Highest`) | `primary` (vibrant accent) |

The battery glyph applies the same rule with a green accent: the outline shell uses the neutral base while the level fill plus percentage uses green.

## Sizes

Canonical sizes specify one size per usage tier on a `24` view box. Notification icons hold `18.dp`. Navbar glyphs hold `22.dp` with `12.sp` labels.

| Tier | Size | Usage |
| :--- | :--- | :---- |
| Hero | `22.dp` | Card header glyphs and primary device marks |
| Card action | `20.dp` | Card action glyphs in the `Quick Actions` group |
| Row and meta | `18.dp` | List rows, battery glyphs, and notification groups |
| Chip and meta small | `16.dp` | Chips, tags, and captions |
| Navbar | `22.dp` | Hub, Files, and Settings destinations with `12.sp` labels |

## Icon-to-action map

The map covers every card and quick action with canonical Solar names in `SolarIcons.kt`. Screen view uses `SolarIcons.Cast` in `PeerDeviceCard.kt`. Battery glyphs resolve through `batteryIconFor`, which maps charge bands and charging state to the matching duotone icon.

A missing allowlist entry throws `IconLoadFailure` at resolve time instead of rendering an unmapped glyph. The `SOLAR_ICON_ALLOWLIST` set is the single source of truth for valid icon names.

## See also

- `design-system.md` overviews the design principles and file map.
- `color.md` records the `primary` and `onSurfaceVariant` tokens behind the layers.
- `components.md` records where each tier appears on cards.
- `navigation.md` records the navbar destinations behind the navbar tier.
