# Widgets

Widgets show peer presence and battery state in a read-only glanceable form with tap-through to the app. The system covers one widget type: an Android home-screen widget showing peer presence plus battery plus quick toggle. The widget reads existing state surfaces and performs no independent transports.

## Content

| Element | Appearance |
| :--- | :--------- |
| Peer presence | Name plus `Connected` or `Not Connected` status in the widget type scale |
| Battery | Green glyph plus percentage matching the in-app `BatteryRow` rule |
| Quick toggle | Single duotone action glyph with the accent highlight, tap-through to the matching in-app action |

## Behavior

The widget refreshes on the same timers as the foreground notification state. Tapping any element opens the app through tap-through. The widget carries the OLED canvas tokens when the app theme is OLED, so the pure-black `000000` base extends to the home screen with recalibrated foregrounds.

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the in-app card contracts behind widget content.
- `color.md` records the OLED canvas behind widget theming.
- `iconography.md` records the duotone treatment behind widget glyphs.
- `states.md` records the connection appearances behind widget states.
