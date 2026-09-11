# Navigation

Navigation lives in the bottom navbar composed in `VortexRoot`. Hub is the default page with paired endpoints, calendar section, and notes carousel. Files shows device file transfers. Settings holds the six categorized sections recorded in `settings.md`. The unified `AppHeader` carries the logo on Hub and title-only on Files and Settings; settings opens from the navbar destination.

## Destinations

| Destination | Glyph | Behavior |
| :--- | :--- | :------- |
| Hub | `SolarIcons.Home` duotone glyph at `22.dp` | Default page with endpoints, CALENDAR section, and notes carousel |
| Files | `SolarIcons.Folder` duotone glyph at `22.dp` | File transfer surface |
| Settings | `SolarIcons.Settings` duotone glyph at `22.dp` | Appearance, Audio, Notifications, Sharing, and Device categories |

Selected destinations render the duotone glyph in `onPrimaryContainer` inside a `64.dp` by `32.dp` `primaryContainer` pill with `16.dp` rounding, paired with the label in `onSurface` with semibold weight. Unselected destinations render the duotone glyph in `onSurfaceVariant` with no pill and the label in `onSurfaceVariant` with medium weight. The pill fill and tint animate across `180.ms` ease-move. Labels hold `12.sp`. The navbar container background uses the theme `background` token to match the app canvas across light, dark, and OLED modes. Screen content pads above the bar.

## Header

The unified `AppHeader` lives at the top of Hub, Files, and Settings in `Common.kt` with `20.dp` horizontal and `16.dp` vertical padding. On Hub, it presents the `36.dp` `VortexLogo` with `8.dp` clipping; Files and Settings render title-only with `showLogo = false`.

## Hub layout

Hub composes in `HomeScreen.kt` as a single scroll column on the `background` fill with `20.dp` horizontal padding and `16.dp` vertical section gaps. From top to bottom the order is the header, optional advisory `HintCard` blocks, the full-width `This device` card, the `PAIRED ENDPOINTS & DEVICES` section header, the two endpoint cards side by side with an aspect ratio of `1.19f` (width/height), the `CALENDAR` header with `CalendarCard`, and the notes carousel. The section header row pairs the uppercase `Label` eyebrow left with live counts right in the form `1 Active · 2 total`. The peer card and earbuds card share one row at equal weight on wide screens and stack full width on narrow screens through wrap-or-stack behavior.

## Files layout

Files is a scroll column with the same `20.dp` horizontal padding and `16.dp` section gaps as Hub. From top to bottom the order is the screen header, the received-files section, and an empty-state guidance block. Received files render one row per file with name, size, and timestamp plus the open action. An empty Files page explains that files transferred between PC and mobile will appear here. File taps open the received file directly via `FileProvider`.

## Settings layout

Settings composes in `SettingsScreen.kt` as a scroll column with `20.dp` horizontal padding and five category cards in fixed order: Appearance, Audio, Notifications, Sharing, and Device. Each card opens with its `SectionLabel` eyebrow and holds its rows at `14.dp` padding with quiet fill dividers between rows. The ADB hint card appears directly below Sharing only while clipboard sync is on without background read access. Full row anatomy and category contents live in `settings.md`.

## Theme and dynamic color

Theme choice covers `Dark`, `Light`, and `OLED` with `Dark` as the default in `UiSettingsStore`. The enum serializes the theme mode explicitly with `fromCode` mapping unknown values to `Dark` as migration-safe fallback. Dynamic color applies only on Android S and above; every other configuration renders the opaque canvas overrides from `Theme.kt`. The fallback accent uses `BrandEmerald` (`2ECC71`). Widgets open the app through tap-through.

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the Hub card contracts.
- `calendar.md` records the CALENDAR section above notes.
- `notes.md` records the carousel order below calendar.
- `settings.md` records the Settings categories in depth.
- `iconography.md` records the navbar glyph tiers.
- `motion.md` records the indicator slide behavior.
- `color.md` records the system-driven accent.
