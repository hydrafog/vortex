# Notes

Notes sit under the calendar section in a horizontal carousel backed by `NoteCarousel.kt` with `NoteStore`. The carousel keeps note capture within reach on the Hub screen. Every note square renders at `132.dp` with `CardCorner` (`16.dp`) rounding, `14.dp` inner padding, and a `surface` fill.

## Carousel geometry

The carousel scrolls horizontally with `12.dp` item spacing. Each square card renders at `132.dp`. The virtualized row preserves smooth scrolling alongside the parent scrollable container.

## Card order and content

The add-new-note square sits at the start of the row before existing notes. Each note square displays the title in `bodyMedium` with medium weight, an optional two-line snippet in `bodySmall` in `onSurfaceVariant`, and a formatted date in `labelSmall`. Tapping a note square opens that note directly in the editor; long-press opens a confirmation dialog to delete the note.

## States

An empty store renders solely the add-new-note square. Blank note titles display an untitled fallback. Snippets without body text drop from the row.

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the card contracts around the carousel.
- `spacing-layout.md` records the carousel spacing tokens.
- `motion.md` records the carousel settle curve.
- `states.md` records the empty and loading appearances.
