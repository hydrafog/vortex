# Notifications

Android notifications keep the four-group `R.layout.notification_vortex` geometry from `VortexNotification.kt` lines `12` through `117`: laptop chip `32dp` row with `18dp` icon and `13sp` semibold text (notification label role), audio chip mirror with tap-to-toggle, lock group `32dp` square button, and clipboard group `32dp` square button pinned right by a weighted spacer.

## Composition

Composition keeps the `NotificationCompat.Builder` form at lines `212` through `220` with themed small icon, `setOngoing(true)`, `setCustomContentView(rv)`, and `DecoratedCustomViewStyle`, plus channel `vortex_bg` at `IMPORTANCE_LOW` with badge disabled and timers `REFRESH_MS 2000`, `OWNER_HOLD_MS 12000`, and `PEER_FRESH_MS 30000`. The small icon is a monochrome themed logo asset that follows Android themed icons: the system tints the glyph.

## Action mapping

Each group maps one tap target through `RemoteViews` pending intents: the laptop chip opens the session, the audio chip toggles the active route, the lock group toggles the paired host lock, and the clipboard group opens quick send. Group icons render the duotone treatment at `18dp` with the neutral base plus accent highlight, dimmed to the base tone when the action is unavailable.

## Redesign bounds

The redesign works within `RemoteViews` operations only (text, color, visibility, pending-intent actions on the four groups).

## See also

- `design-system.md` overviews the design principles and file map.
- `iconography.md` records the duotone treatment behind notification glyphs.
- `logo.md` records the themed logo asset behind the small icon.
- `states.md` records the pairing and loading appearances.
