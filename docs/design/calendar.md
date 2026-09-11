# Calendar

The Hub shows upcoming dates above notes. The `CALENDAR` section sits between the paired endpoints row and the `NOTES` carousel in `HomeScreen.kt`. The section holds a week strip and the selected-day list. Notes with a due date appear alongside events for the selected day.

## Hub order

Hub composes as a single scroll column with `20.dp` padding and `16.dp` section gaps. From top to bottom the order is the header, advisory hints, the `This device` card, the endpoints row, the `CALENDAR` header with `CalendarCard`, and the `NOTES` header with `NoteCarousel`. The calendar header uses the same label style with letter spacing as the notes header. `CalendarCard` uses `CardCorner` at `16.dp`, `12.dp` internal gaps, and theme tokens `surface`, `onSurface`, `primaryContainer`, and `error`.

## Provider contract

`CalendarProvider` exposes the event list as a `StateFlow` with `forDate`, `add`, and `remove`. `LocalCalendarProvider` delegates to `CalendarStore`, which persists `calendar.json` in `filesDir` with private mode. `RicelinFileProvider` reads a Ricelin `events.json` file and maps it to the same model. The backend choice persists in `UiSettingsStore` under `calendarBackend` with values `local` and `ricelin`. The default is `local`. The Settings screen holds the picker under the calendar section.

## Event model

Events carry `id`, `date`, `endDate`, `time`, `endTime`, `text`, and `recur`. Dates use `YYYY-MM-DD` and times use `HH:MM` or an empty string for all-day. `recur` is empty for a one-off, `year` for a yearly entry, and `month` for a monthly entry. A yearly entry matches on the `MM-DD` tail. A monthly entry matches on the `DD` tail where the day exists. A one-off entry covers the inclusive string range from `date` to `lastDay`, where `lastDay` is `endDate` when set and `date` otherwise. A recurring entry ignores `endDate`. Times outside `HH:MM` clean to an empty string. New ids use UUIDs to avoid collisions.

## Day matching

`CalendarCard` filters events with `forDate` for the selected day and sorts empty times first, then lexicographic `HH:MM`. The week strip picks days by tap or horizontal drag, with the selection pill and day list animating on the press and travel beats. Due notes join the same list when the `dueAt` timestamp falls inside the selected day in the device default time zone. The match truncates each timestamp to its day window in that zone. Overdue notes render in `error`. Every day shows an add affordance that opens a dialog for title plus optional `HH:MM` time; saving persists through the provider and appears for the selected day. A corrupt `calendar.json` loads as an empty list. A corrupt Ricelin file keeps the last good in-memory list.

## See also

- `navigation.md` records the Hub order with calendar above notes.
- `notes.md` records the carousel below the calendar section.
- `components.md` records the card contracts.
- `settings.md` records the Settings categories including the calendar backend picker.
