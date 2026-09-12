# Calendar

The Hub shows upcoming dates above notes. The `CALENDAR` section sits between the paired endpoints row and the `NOTES` carousel in `HomeScreen.kt`. The section holds a week strip and the selected-day list. Notes with a due date appear alongside events for the selected day.

## Hub order

Hub composes as a single scroll column with `20.dp` padding and `16.dp` section gaps. From top to bottom the order is the header, advisory hints, the `This device` card, the endpoints row, the `CALENDAR` header with `CalendarCard`, and the `NOTES` header with `NoteCarousel`. The calendar header uses the same label style with letter spacing as the notes header. `CalendarCard` uses `CardCorner` at `16.dp`, `12.dp` internal gaps, and theme tokens `surface`, `onSurface`, `surfaceContainerHigh`, and `error`.

## Provider contract

`CalendarProvider` exposes events as a `StateFlow` plus `forDate`, `add`, `remove`. `add` carries `date`, `time`, `text`, `endDate`, `endTime`, and `recur`. `LocalCalendarProvider` delegates to `CalendarStore` for `calendar.json` in `filesDir`. `RicelinFileProvider` persists `events.json` in `filesDir` with the same seven keys. Both publish sorted by `date`, all-day first, then `time`. The backend persists in `UiSettingsStore` under `calendarBackend` (`local`, `ricelin`; default `local`). `HomeScreen` unions missing entries by `id` into the active file on switch and reloads on resume.

## Event model

Events carry `id`, `date`, `endDate`, `time`, `endTime`, `text`, and `recur`. Dates use `YYYY-MM-DD` and times use `HH:MM` or an empty string for all-day. `recur` is empty for a one-off, `year` for a yearly entry, and `month` for a monthly entry. A yearly entry matches on the `MM-DD` tail. A monthly entry matches on the `DD` tail where the day exists. A one-off entry covers the inclusive string range from `date` to `lastDay`, where `lastDay` is `endDate` when set and `date` otherwise. A recurring entry ignores `endDate`. Times outside `HH:MM` clean to an empty string. New ids are numeric strings past the highest numeric id.

## Day matching

`CalendarCard` filters events with `forDate` for the selected day and sorts empty times first, then lexicographic `HH:MM`. The week strip features continuous horizontal dragging with 1:1 finger tracking, fling momentum projection, and spring snapping to the nearest day card. Day cards are dynamically sized based on their continuous distance from the strip center: the active center card is largest (`50.dp` width, `72.dp` height, `20.sp` bold day numeral, `12.sp` weekday) and smoothly scales down toward the perimeter (`32.dp` width, `44.dp` height, `11.sp` day numeral). All cards sit on a downward parabolic curve ($y_{\text{dip}} = 14\text{dp} \cdot \max(0, 1 - (d/3)^2)$), causing the center card to dip down prominently toward the event list. Due notes join the list when the `dueAt` timestamp falls inside the selected day in the device default time zone. Overdue notes render in `error`. A single Add affordance resides in the card header with a neutral `surfaceContainerHigh` tile and duotone icon; saving persists through the provider and appears for the selected day. A missing file self-heals to empty. A corrupt body keeps the last-good list on either backend.

## See also

- `navigation.md` records the Hub order with calendar above notes.
- `notes.md` records the carousel below the calendar section.
- `components.md` records the card contracts.
- `settings.md` records the Settings categories including the calendar backend picker.
