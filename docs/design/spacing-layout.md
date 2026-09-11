# Spacing and layout

Geometry centers on `CardCorner` at `16.dp` in `Common.kt`. One spacing rhythm runs from the screen edge to card content, so padding, gaps, and rounding share a single scale. Device cards use a taller aspect ratio (`0.84f`) for comfortable content hierarchy.

## Spacing scale

The scale steps in `4.dp` increments with named stops for screen, section, card, and row density.

| Token | Value | Usage |
| :--- | :--- | :---- |
| Screen horizontal padding | `20.dp` | Hub, Files, and Settings content edges |
| Section gap vertical | `16.dp` | Gaps between screen sections |
| Unrelated section gap | `24.dp` | Gaps between unrelated blocks such as cards and notes |
| Related card gap | `12.dp` | Gap between the two endpoint cards |
| Card inner padding | `16.dp` | Device card content |
| Deep card padding | `20.dp` | `SurfaceCard` content |
| Row padding | `12.dp` horizontal, `10.dp` vertical | `PairedRow` density |
| Hint padding | `16.dp` horizontal, `12.dp` vertical | `HintCard` density |
| Detail gap | `12.dp` | Gaps between title, detail lines, and card groups |
| Carousel item spacing | `12.dp` | Gaps between note squares in `NoteCarousel` |
| Quick action tile | `36.dp` | Uniform action tile in `PeerDeviceCard` |

## Corners

Corners stay flat-filled at any radius.

| Token | Value | Usage |
| :--- | :--- | :---- |
| `CardCorner` | `16.dp` | Device cards and note squares |
| `SurfaceCard` container | `12.dp` | Full-width surface containers |
| Icon tile | `12.dp` | `42.dp` `CardHeader` and `New Note` tiles |
| Quick action tile | `8.dp` | `36.dp` action tiles in `PeerDeviceCard` |
| Secondary rows | `8.dp` | `PairedRow` rows |
| Pills and tags | Full rounding | Badges and navbar indicator pills |

## Screen grid

Screen content pads `20.dp` horizontally with `16.dp` vertical section gaps. Device cards sit in a two-card row with `12.dp` spacing, each taking equal `weight(1f)`.

The Hub order from top to bottom is the header, optional advisory `HintCard` blocks, the full-width `This device` card, the `PAIRED ENDPOINTS & DEVICES` section header, the two endpoint cards side by side, the `CALENDAR` section with `CalendarCard`, and the notes carousel. The bottom navbar holds Hub, Files, and Settings with safe-area insets; content pads above the navbar.

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the card and carousel contracts behind the grid.
- `notes.md` records the note square geometry in depth.
- `navigation.md` records the navbar and Hub order.
