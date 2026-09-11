# Logo

The brand mark is Solar Black Hole Bold Duotone from `assets/vortex_solar_source.svg` on a `512` canvas with `56` padding translate, recolored to `1AE76F`. Attribution follows CC BY 4.0 to 480 Design as recorded in `assets/SOLAR_LICENSE.txt` lines `3` through `10`.

## Consumers

| Consumer | Contract |
| :--- | :------- |
| `VortexLogo.kt` | Brand mark at `36.dp` default size, tinted with the scheme `primary` token |
| `R.drawable.ic_vortex_logo_vector` | `24dp` vector on a `24` viewport with the orbit path at `0.5` alpha and the core path at full alpha |
| `assets/vortex_logo.png` | Full-color raster source for launcher and sharing surfaces |
| Notification small icon | Monochrome themed asset derived from the orbit geometry; the system tints the glyph while the full-color mark never appears in the status bar |

## Flat restyle

The flat restyle relies on the duotone opacity pair plus solid fills. The orbit path renders the neutral base treatment while the core renders the accent highlight, mirroring the interface duotone rule with brand accents. Interface glyphs stay Solar Duotone at stroke `1.5` to `1.8` while the brand mark stays Bold Duotone.

## See also

- `design-system.md` overviews the design principles and file map.
- `iconography.md` records the duotone rule behind interface glyphs.
- `notifications.md` records the themed small icon consumer.
- `navigation.md` records the header brand placement.
