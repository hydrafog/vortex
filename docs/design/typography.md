# Typography

Body text uses `Satoshi` and titles use `Space Grotesk`. The pairing rationale is geometric: `Space Grotesk` carries a display grotesque voice with distinctive letterforms at title scale while the neutral grotesque `Satoshi` body stays quiet, so titles read as distinct without a second licensed-vendor family.

## Body face

`Satoshi` covers all body copy, values, captions, and labels in weights `Regular 400`, `Medium 500`, and `Bold 700`. Android loads `Satoshi` through bundled font resources consumed with Compose `FontFamily`. The Compose fallback stack is `Satoshi, system-ui, Roboto, sans-serif`. The `Satoshi` load boundary keeps the fallback stack visible within glyph metrics.

| Weight | Value | Usage |
| :----- | :---- | :---- |
| `Regular` | `400` | Body copy and captions |
| `Medium` | `500` | Names, values, and emphasized body text |
| `Bold` | `700` | Strong emphasis inside body copy |

## Title face

`Space Grotesk` covers screen titles, card names, and section headers in semibold only. The Compose title fallback is `Space Grotesk, Satoshi, system-ui, sans-serif`.

## Type scale

The ideal type scale pairs each role with one face, size, weight, and line height. Display titles use `Space Grotesk` semibold; body and supporting text uses `Satoshi`. No role substitutes a second size or weight.

| Role | Face | Size | Weight | Line height | Usage |
| :--- | :--- | :--- | :----- | :---------- | :---- |
| Display | `Space Grotesk` | `22.sp` | Semibold | `28.sp` | Screen titles such as Hub |
| Title | `Space Grotesk` | `17.sp` | Semibold | `22.sp` | Card names and section headers |
| Body | `Satoshi` | `15.sp` | Regular | `20.sp` | Primary copy and card details |
| Caption | `Satoshi` | `13.sp` | Regular | `17.sp` | Hints, counts, and secondary metadata |
| Label | `Satoshi` | `12.sp` | Semibold | `16.sp` | Eyebrows such as `PAIRED ENDPOINTS & DEVICES` and `Quick Actions` |
| Notification label | `Satoshi` | `13.sp` | Semibold | `17.sp` | Notification chip labels |
| Navbar label | `Satoshi` | `12.sp` | Medium | `16.sp` | Hub, Files, and Settings destinations |

Tracking stays at the platform default for body and titles; the `Label` role uses the default tracking with uppercase text set at the call site.

## See also

- `design-system.md` overviews the design principles and file map.
- `color.md` records the `onAccentFor` contrast rule behind text colors.
- `spacing-layout.md` records the layout grid behind title placement.
- `components.md` records where each type role appears on cards.
