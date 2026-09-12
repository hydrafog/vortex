# Settings

Settings lives in the navbar destination composed in `VortexRoot` through `SettingsScreen.kt` with persistence in `UiSettingsStore`. Items group in six labeled categories in fixed order: Appearance, Audio, Notifications, Sharing, Device, and Calendar. Each category is one card with one `SectionLabel` eyebrow above it; settings opens only from the navbar. The accent follows the user-selected accent preset or device dynamic color, with Vortex Green (`2ECC71`) as default.

## Category order

Categories render top to bottom in this order with `16.dp` section gaps between cards. The order never changes and no category merges into another.

| Order | Category | Content |
| :--- | :------- | :------ |
| 1 | Appearance | Language picker, theme picker, and accent color picker |
| 2 | Audio | Smart switch toggle for earbuds handoff |
| 3 | Notifications | Notification mirroring toggle plus peer notification display toggle |
| 4 | Sharing | Clipboard sync toggle plus file auto-accept toggle |
| 5 | Device | Screen-control action row into the system Accessibility page |
| 6 | Calendar | Calendar backend picker (`local` or `ricelin`) |

## Appearance

Appearance holds language, theme, and accent color pickers in one card. Language orders English first with English, Uzbek, and Russian segmented choices; selection persists through `UiSettingsStore` with fallback to the English table when a key is missing. Theme offers dark, light, and OLED segmented choices with dark as the default. Theme segmented buttons display clean text labels without leading icons, while the picker section header pairs a duotone glyph with a semibold `bodyMedium` label. Selected segments use the `primaryContainer` fill with `onPrimaryContainer` text. The accent color picker presents circular swatches for the presets recorded in `color.md`, with Vortex Green (`2ECC71`) as the default selection marked by a check glyph.

## Audio

Audio holds the smart-switch toggle in one card with its headset duotone glyph. The row states what the switch does (earbuds move between phone and laptop) in the hint line, so the category name plus hint fully explain the feature with no cross-reference.

## Notifications

Notifications holds two toggles in one card: notification mirroring with its bell duotone glyph and peer notification display with its active-bell glyph. Notification mirroring controls whether phone notifications appear on paired hosts; peer notification display controls whether peer notifications surface on this device. Hints state each direction so the pair reads as a matched set.

## Sharing

Sharing holds clipboard sync with its paste duotone glyph and file auto-accept with its download duotone glyph in one card. Clipboard sync shows the ADB hint card below the category when background read access is missing, with the exact `adb` command in monospace on the `background` fill. File auto-accept states that incoming files land in Downloads.

## Device

Device holds the screen-control action row with its touch duotone glyph, status text, and chevron into the system Accessibility page. The row uses the same icon tile plus title plus hint structure as toggle rows with a status label instead of a switch.

## Calendar

Calendar holds the picker in one card with its calendar duotone glyph. `local` reads `calendar.json`, `ricelin` reads `events.json` in `filesDir`. Switching unions by `id` into the active file; values and the `local` default stay unchanged.

## Row anatomy

Every settings row lives in `SettingsScreen.kt` and shares one anatomy. A `36.dp` duotone icon tile with `10.dp` rounding opens the row, followed by a title in semibold `bodyMedium` with a hint in `bodySmall` below it, closed by a trailing switch or status label. Rows pad `14.dp`; rows inside a card separate with a quiet fill divider step. Tapping anywhere on a toggle row flips the switch. Disabled toggles hold `onSurfaceVariant` glyphs.

## See also

- `design-system.md` overviews the design principles and file map.
- `navigation.md` records the Settings destination and page order.
- `color.md` records the system-driven accent.
- `iconography.md` records the duotone treatment behind settings glyphs.
- `states.md` records the selected and disabled appearances.
