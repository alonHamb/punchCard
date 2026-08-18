# Architecture

A technical walkthrough of how PunchCard is put together. See
[`README.md`](README.md) for the feature-level overview and
[`SETUP.md`](SETUP.md) for how to build and run it.

## Stack

- **Kotlin** + **Jetpack Compose** (Material3) for the UI — no XML
  layouts.
- **Room** (SQLite) for local persistence — the only data store the app
  has.
- **WorkManager** for real OS-level background scheduling (the nightly
  backup check keeps running even if the app is closed, which nothing
  short of a native app can do).
- **Storage Access Framework** (`DocumentFile`, `OpenDocumentTree`) for
  writing the backup CSV into a user-chosen folder without needing
  OAuth, a cloud SDK, or the `INTERNET` permission.
- **Jetpack Glance** (`glance-appwidget`) for the home-screen widget —
  Compose-style declarative UI compiled down to `RemoteViews`, so no
  hand-written widget XML layouts.
- No networking library, no HTTP client, no `INTERNET` permission —
  by design (see `AndroidManifest.xml`'s comment block).

## Data model

Two Room entities, both in `data/`:

**`LogEntry`** (table `log_entries`, primary key `date` as `"YYYY-MM-DD"`)
```kotlin
data class LogEntry(
    val date: String,
    val startTime: String? = null,   // "HH:mm", 24h, device-local
    val endTime: String? = null,
    val hours: Double? = null,       // computed once both times are set
    val money: Double? = null,       // hours * that day's effective hourly rate
    val backedUp: Boolean = false,   // cleared on any edit, so edits always re-back-up
    val lastUpdated: Long = System.currentTimeMillis(),
)
```
A row can exist with only `startTime` set (Start logged, End not yet) —
`hours`/`money` stay `null` until both times are present.

**`PaySettings`** (table `pay_settings`, primary key `effectiveDate`)
```kotlin
data class PaySettings(
    val effectiveDate: String,  // applies from this date onward
    val hourlyRate: Double,
    val creditPoints: Double,
    val pensionPct: Double,
    val overtimeEnabled: Boolean = true,
    val savingsPct: Double = 0.0,
)
```
`overtimeEnabled` was added in schema v2 (`AppDatabase.MIGRATION_1_2`,
a plain `ALTER TABLE ... ADD COLUMN ... DEFAULT 1`) — existing rows
default to enabled. `savingsPct` was added in schema v3
(`MIGRATION_2_3`, same pattern, `DEFAULT 0.0`) — existing rows default
to no savings target, so nothing changes until one is set.
This is **append-only** — saving new settings inserts a new row keyed by
today's date rather than overwriting the old one. Every calculation
looks up "whichever settings row was in effect on the date being
calculated" (`PaySettingsDao.getForDateOrBefore`, falling back to the
earliest row if the date predates all of them). This is what makes
changing your rate today never rewrite last month's numbers.

`HoursRepository` (`data/HoursRepository.kt`) is the single entry point
both the UI and the background backup job go through — nothing outside
it touches the DAOs directly.

## Pay calculator

`logic/PayCalculator.kt` is a pure Kotlin object with no Android
dependencies, so it's fast to unit-test in isolation
(`app/src/test/.../PayCalculatorTest.kt`).

- `computeHours(start, end)` — end minus start, minus any overlap with
  the fixed `BREAK_WINDOWS` (10:00–10:10, 12:40–13:20, 15:00–15:10).
- `computeMoney(hours, rate)` — straight multiplication.
- `computeMonthSummary(...)` — sums a month's entries using each entry's
  own effective-dated rate (so a mid-month rate change is handled
  correctly), then applies progressive income tax brackets, National
  Insurance + health tax (two-tier rate below/above a threshold, capped
  at a ceiling), and a flat pension % — using the settings in effect on
  the last logged day of the month for the tax-side numbers.

All constants (tax brackets, credit point value, NI threshold/ceiling/
rates) are hardcoded at the top of the file with a comment marking them
as **2026 figures to update yearly**. This is explicitly an *estimate*:
it does not model the real ~35% pension tax-credit mechanism, marginal
relief, multiple employers, or non-resident status.

### Overtime

`computeDailyPay(hours, hourlyRate, overtimeEnabled)` is the one place
overtime is computed, called by both `computeMoney` (a single day) and
`computeMonthSummary` (per entry, summed into `regularPay`/`overtimePay`
which always sum to `gross`) — so a day's Money figure and its
contribution to the month's Gross always agree, and the Home screen's
month breakdown can show "Regular pay" and "Overtime pay" as their own
line items (only when the month actually has overtime hours — otherwise
it collapses back to a single "Gross pay" row, unchanged from before
overtime existed). The
model is deliberately simple and per-day only: the first
`REGULAR_DAILY_HOURS` (8) are regular pay; the next
`OVERTIME_TIER1_HOURS` (2) — hours 9 and 10 — are `OVERTIME_RATE_TIER1`
(125%); anything past hour 10 is `OVERTIME_RATE_TIER2` (150%). It does
not aggregate hours across a week or account for shortened Friday/
pre-holiday days, so — like the tax brackets — it's an estimate, not a
legal payroll calculation. `PaySettings.overtimeEnabled` is a per-user
toggle (default on) for the rare case of an overtime-exempt role.

### Savings

`MonthSummary.savings` and `.leftToSpend` are a deliberately different
kind of figure from everything above them: `savings = net *
(savingsPct / 100)` and `leftToSpend = net - savings`, computed at the
very end of `computeMonthSummary` — after `net` is already final. This
is a **set-aside target, not a payroll deduction**: unlike pension
(which reduces `net` itself), changing `savingsPct` never changes what
`net` means anywhere else in the app (Home screen's "Net income" row,
the home-screen widget). It only ever adds two *additional* figures on
top. `PayCalculatorTest` has an explicit regression test asserting this
(computing the same month with `savingsPct` at 10% and at 0% and
checking `net` is identical). The Home screen only shows the
Savings/Left-to-spend rows when `savingsPct > 0`, so someone who's never
touched the setting (default `0.0`, per `MIGRATION_2_3`) sees no change
at all.

## UI / state

`ui/MainViewModel.kt` exposes everything the three screens need as
`StateFlow`s, mostly built directly from Room's reactive `Flow` queries
(so edits made anywhere — Manage screen, background backup — show up
live everywhere else without manual refresh calls):

- `today` — reactive to the current date via a `_todayDate` MutableStateFlow
  and `flatMapLatest`, refreshed periodically so the UI rolls over to a
  new day even if left open across midnight.
- `recent`, `pendingBackupCount`, `paySettings` — direct passthroughs of
  Room `Flow`s.
- `monthSummary` — a one-shot suspend calculation (`getMonthSummary`),
  manually reloaded (`loadMonth`) whenever an edit could have changed it.
- `manageEntries` — reactive per-month list backing the Manage screen,
  via `_manageMonth.flatMapLatest { repo.observeForMonth(it) }`.

Start/End mode is **purely data-driven**, not clock-driven: the button
shows "End Shift" if and only if today's `LogEntry.startTime` is set
(`HoursRepository.hasStartedToday`/`logNext` re-read the DB fresh on
every tap, so it's race-safe against stale UI state).

Three screens, switched via a simple `Screen` enum in `MainActivity.kt`
(`AppRoot` composable) — no navigation library, since there are only
three destinations and none of them need deep linking or a back stack
beyond a single "close" callback. `AppRoot` registers a
`BackHandler(enabled = screen != Screen.Main)` that reuses that same
`onClose` logic (jump back to `Screen.Main`) for the system/gesture
back button too — enabled only away from Home, so on Home itself
there's no handler at all and back falls through to the platform
default (finish the activity), which is exactly "exit the app" since
this is the app's only/root activity.

## Backup system

**Scheduling** (`backup/BackupScheduler.kt`): a `PeriodicWorkRequest`
for `BackupWorker` every 15 minutes (WorkManager's minimum), constrained
to `NetworkType.UNMETERED` as a coarse pre-filter so the OS doesn't even
wake the app on a metered connection. Registered once, in
`PunchCardApp.onCreate()`.

**The gate** (`backup/BackupWorker.kt`): on each tick, unless forced
(see below), it checks `isNightWindow()` (18:00–06:00 by device clock)
and `isOnWifi()` (via `ConnectivityManager`) — either check failing is
an instant no-op. If both pass, it fetches every entry with both times
set and `backedUp = false`, groups them by month, and writes each
month's rows via `CsvBackup`. If any of those days is the last calendar
day of its month, it also computes and writes that month's summary row.
Every successfully-written entry gets `backedUp` set back to `true`.

**Force path**: `BackupScheduler.triggerNow()` enqueues a one-time
`WorkRequest` with `KEY_FORCE = true` and **no** network constraint —
`BackupWorker` skips both the time-window and wifi checks entirely when
that flag is set. This is what the Home screen's "Back up now" button
calls, so it writes immediately regardless of the time of day or
connection type.

**The files** (`backup/CsvBackup.kt`): one `HoursBackup-YYYY-MM.csv`
per month (columns: Date, Start Time, End Time, Hours, Money) and one
`MonthlySummary-YYYY-MM.csv` per month once that month is complete
(Month, Total Hours, Hourly Rate, Credit Points, Gross, Income Tax,
NI + Health, Pension %, Pension, Net). Both are **upserts by key**
(date, or month) — the existing file is read, the row for that key is
replaced or appended, and the whole file is rewritten sorted by key.
This makes re-running a backup, or backing up an edited day, always
safe: it overwrites rather than duplicates. All I/O goes through
`DocumentFile`/`ContentResolver` against the SAF tree URI the user
granted in Settings — never a raw file path.

**Known limitation**: deleting a day via the Manage screen removes it
from the local database, but if that day had already been backed up,
its row is *not* retroactively removed from the CSV file already
written to the backup folder (deletes aren't pushed into existing
backup files). Editing a day, by contrast, works cleanly — it clears
`backedUp`, so the corrected row overwrites the old one on the next
backup pass.

## Restoring after an uninstall

Because everything lives in a local Room database, uninstalling the app
(or losing the phone) wipes it — the backup CSVs, sitting outside app
storage in whatever folder the user chose, are what actually survive.
`CsvBackup.readAllDailyRows(context, folderUri)` (blocking file I/O,
called from the ViewModel via `withContext(Dispatchers.IO)`) scans that
folder for every `HoursBackup-*.csv` file across all months and parses
every row back into `LogEntry` objects, marked `backedUp = true` since
they're already reflected in a backup file. `HoursRepository.
importFromBackup(entries)` then merges them in: for each parsed entry,
if the local DB has no row for that date yet, it's inserted; if one
already exists (e.g. the user re-logged today before restoring), it's
left alone. This makes restoring safe to run at any time, including
after some fresh local activity. Pay settings are intentionally *not*
reconstructed from the CSVs — the daily/monthly backup files don't carry
the full effective-dated settings history, only a snapshot of whichever
settings were active when each row was written — so Settings prompts
the user to re-enter their rate/credit points/pension after a restore.

## Spreadsheet import

Settings' "Import from spreadsheet" reads a user-picked `.xlsx` file and
adds any day it finds that isn't already logged locally — same
never-overwrite rule as restoring from a CSV backup, and the same
split between pure parsing logic and a thin Android I/O wrapper used
throughout `backup/`:

- **`backup/XlsxFormat.kt`** (`internal object`, pure Kotlin, no
  Android deps) does the actual parsing, covered by `XlsxFormatTest`.
  A `.xlsx` is a zip of XML parts (OOXML); this reads exactly two of
  them — one worksheet's `sheet*.xml` and `sharedStrings.xml` (cells
  typed `t="s"` store an index into this list rather than inline text)
  — via hand-rolled regexes rather than a full XML parser or a
  third-party spreadsheet library, consistent with `CsvFormat`'s
  approach to CSV. `pickSheetEntryName` prefers `sheet1.xml`, falling
  back to whichever worksheet part sorts first if a workbook somehow
  lacks one (multi-sheet `.xlsx` files aren't otherwise specially
  supported — only the one chosen worksheet is read).
  - Columns are matched by **header text**, not fixed column letters:
    the first row found is searched case-insensitively for cells
    containing "date", "start", and "end" as substrings — so a sheet
    with the pay/tax columns this feature was built against (which
    come *after* the three it needs, and are otherwise ignored
    entirely) still imports correctly, and so would one with those
    three columns in a different order.
  - `excelSerialToIsoDate`/`excelFractionToTime` convert Excel's
    numeric date-serial (days since the 1899-12-30 epoch) and
    time-of-day fraction (0.0–1.0) representations to this app's
    `"YYYY-MM-DD"`/`"HH:mm"` storage format, rejecting non-finite or
    out-of-range values defensively — the same posture as the manual
    entry fields, just applied to a whole external file instead of a
    text box. A date cell typed as *text* rather than a real Excel
    date (`t="s"` on what should be the date column) is also rejected
    rather than misread as a shared-string index.
  - A row is skipped entirely if it has no valid date, or if it has
    neither a start nor an end time — this is what makes the
    formula-only template rows past a sheet's real data (present in
    the format this was built against, since its formulas are
    dragged down further than the logged days) come back empty rather
    than as bogus entries.
- **`backup/XlsxImport.kt`** is the thin wrapper: opens the picked
  `content://` URI via `ContentResolver`, and does a single forward-only
  pass over every zip entry with `java.util.zip.ZipInputStream` —
  SAF streams aren't always seekable, so this can't jump straight to
  the parts it wants by name — keeping only the chosen worksheet and
  shared-strings text before handing them to `XlsxFormat`. Any
  exception (not a real `.xlsx`, corrupt zip, unexpected internal
  layout) is caught and treated as "nothing found", same forgiving
  posture as `CsvBackup`'s file reads.
- **`HoursRepository.importFromSpreadsheet(entries)`** merges the
  parsed rows in: for each one, if the local DB has no row for that
  date yet, it goes through `setEntryTimes` (recomputing hours/money
  against whichever pay settings are in effect on that date, and
  queuing the day for the next backup) exactly as if it had been typed
  into the Manage screen's "Add day" dialog by hand; a date that
  already has a local entry is left untouched. Unlike
  `importFromBackup`, spreadsheet rows never carry a precomputed
  hours/money figure — the file has no idea what this app's pay
  settings are — so they can't just be upserted as-is.
- Pay/tax settings are never read from the spreadsheet (even though
  the format this was built against happens to carry an hourly rate
  and credit points in its own summary columns) — same reasoning as
  CSV restore: there's no reliable way to map a single snapshot value
  onto this app's effective-dated settings history, so the app's
  current Settings apply to every imported day instead.

## Home-screen widget

`widget/` — practically the whole Home screen (`MainScreen.kt`)
reimplemented for Glance/RemoteViews, minus the scrollable Recent Days
list (Android widgets handle a nested scroll region worst of
everything Compose can do, so it was deliberately left out). No separate `logic`
split, since there's no calculation of its own to isolate — everything
here is either Android/Glance plumbing or a direct call into the same
`HoursRepository`/`PayCalculator`/`BackupScheduler`/`BackupPreferences`
the rest of the app already uses:

- **`PunchCardWidget.kt`** — the `GlanceAppWidget`. `provideGlance`
  resolves *everything* up front — today's `LogEntry` (Start/End mode),
  the persisted "which month is being viewed" state (see
  `ShiftMonthAction.kt` below), that month's `PayCalculator.MonthSummary`,
  the pending-backup count (`HoursRepository.getPendingBackupEntries().size`),
  and the backup folder name (`BackupPreferences`) — then passes all of
  it as plain values into `WidgetContent`, a single composable that
  mirrors `MainScreen.kt`'s section-by-section structure (header, the
  Start/End button, a compact "Today" row, the month breakdown with
  its `‹`/`›` nav, then the backup row pinned to the bottom via
  `GlanceModifier.defaultWeight()` on a spacer above it). This
  "resolve everything before composing" approach is deliberate: each
  `update()`/`updateAll()` call fully re-runs `provideGlance` from
  scratch rather than incrementally recomposing an existing tree, so
  there's no benefit to reactive reads (`currentState()`) inside the
  composable itself — plain suspend calls beforehand are simpler and
  exactly as fresh. `cornerRadius` (rounded corners) only takes effect
  on Android 12+ — a Glance/platform limitation, not a bug; older
  phones just see square corners.
- **`LogNowAction.kt`** — the Start/End tap handler (`ActionCallback`).
  Mirrors `MainViewModel.logNow()` exactly: builds a repository the
  same way, calls `HoursRepository.logNext(date, time)` (which re-reads
  the DB fresh to decide Start vs. End, so it's never fooled by a stale
  widget), then calls `PunchCardWidget().update(context, glanceId)`.
- **`ShiftMonthAction.kt`** — the `‹`/`›` tap handler, and where the
  "viewed month" persistence lives. `GlanceAppWidget.stateDefinition`
  defaults to `PreferencesGlanceStateDefinition` (a small per-widget-
  instance key/value store backed by DataStore, keyed by `GlanceId`) —
  no extra setup needed to use it. `VIEWED_MONTH_KEY` stores a
  `"YYYY-MM"` string; absent means "current month". The action reads
  the current value via `getAppWidgetState`, computes the shifted
  month, refuses to go past the current month (same "no future months"
  guard `MainViewModel.shiftMonth` has), writes it back via
  `updateAppWidgetState`, then re-renders. Each widget instance
  remembers its own viewed month independently, same as the in-app
  Home/Manage screens each have their own separately-tracked month.
- **`BackupNowAction.kt`** — calls `BackupScheduler.triggerNow(context)`,
  same as the Home screen's button. Doesn't re-render the widget itself
  since the actual backup runs asynchronously via WorkManager — the
  pending-day count only changes once that finishes and something else
  (a `MainViewModel` mutation, or the periodic refresh) triggers a
  redraw, same as the in-app button doesn't refresh anything
  synchronously either.
- **`PunchCardWidgetReceiver.kt`** — the trivial `GlanceAppWidgetReceiver`
  the manifest points at; just supplies the `GlanceAppWidget` instance.
- **`res/xml/punchcard_widget_info.xml`** — sized generously
  (`minWidth="250dp" minHeight="380dp"`, `resizeMode="horizontal|vertical"`)
  since this now shows a lot more than a compact glance figure; a user
  who resizes it smaller than its content needs will see that content
  clipped rather than scrolled, the same practical limitation any
  non-list Android widget has. `updatePeriodMillis` is set to 30
  minutes, the OS-enforced minimum for this mechanism — it's only a
  safety-net refresh for the rare case of the calendar day rolling over
  while the widget sits untouched. Real freshness comes from explicit
  `update()`/`updateAll()` calls: each action updates right after its
  own tap, and `MainViewModel` calls `PunchCardWidget().updateAll(context)`
  at the end of every function that mutates logged days or pay settings
  (`logNow`, `updateEntryTimes`, `deleteEntry`, `savePaySettings`,
  `restoreFromFolder`, `importFromSpreadsheet`) — so editing a day in
  the Manage screen, or restoring a backup, reflects on an
  already-placed widget immediately rather than waiting up to 30
  minutes. `updateAll` is a no-op if the widget hasn't been placed on
  any home screen, so these calls are safe regardless.
- Not covered by the unit test suite — Glance composables render to
  actual `RemoteViews`/`AppWidgetManager` calls, which (like the rest of
  the Compose UI) need a device/emulator to exercise for real; the
  calculation and Start/End-mode logic it calls into (`PayCalculator`,
  `HoursRepository.logNext`/`getMonthSummary`) is already covered
  independently.

## Manage screen

`ui/ManageScreen.kt` is the one screen that writes outside the normal
Start/End flow. It calls `HoursRepository.setEntryTimes(date, start,
end)` directly — same recompute logic as a normal tap (hours/money
recalculated if both times end up present, cleared if either is
missing), and always clears `backedUp`, so an edited or newly-added day
— including one from a past month that had already been backed up — is
picked up by the next backup pass. `deleteEntry(date)` removes the row
entirely. The "Add day" dialog's date field is free text, so it's not
limited to the month currently being browsed — any past date works.

**Date/time entry**: the dialog's fields use a Number-type keyboard,
which on Android has no `:`, `-`, or `/` key — `normalizeTime`/
`normalizeDate` (top of `ManageScreen.kt`) accept plain digits
(`0900`, `17082026`) as well as the punctuated forms, and normalize
either to the canonical stored format (`normalizeDate` returns ISO
`YYYY-MM-DD`; see `DateFormat.kt` for the separate display-only
DD/MM/YYYY conversion used everywhere dates are shown, including the
non-editable date in "Edit day" and each Manage row).

**Duplicate-day confirmation**: only in "Add day" mode (the date field
is free text there, unlike "Edit day" where it's fixed to the row being
edited), Save first calls `onCheckExisting(date)` — wired to
`MainViewModel.getEntryForDate` → `HoursRepository.getEntry` — before
writing anything. If that date already has a logged entry, the
validated values are held in `conflict` state and a second confirmation
`Dialog` appears showing the existing entry, instead of saving
immediately; Cancel discards nothing but the popup, Overwrite proceeds
with the original save.

## Testing

`app/src/test/java/com/punchcard/app/` has a full pure-JVM unit test
suite (`./gradlew test`, no emulator needed) covering every calculation
and parsing rule in the app, one file per area:

- `logic/PayCalculatorTest.kt` — `PayCalculator` in isolation: break
  subtraction (including a shift starting mid-break and one crossing
  midnight), leap-year last-day-of-month, overtime tiers (125%/150%,
  on/off, including the exact 10-hour tier boundary), income tax bracket
  boundaries across all seven brackets, NI + health tax's threshold and
  ceiling, the credit-points floor that keeps tax from going negative,
  `settingsForDate`'s fallback to the earliest known settings row for a
  date that predates all of them, full month summaries (a mid-month
  rate change, an in-progress day with no hours yet, the empty-entries
  case), and savings (correctly a percentage of net, and — the
  important regression to guard — that changing `savingsPct` never
  changes `net` itself).
- `backup/CsvFormatTest.kt` + `backup/BackupWorkerLogicTest.kt` — the
  CSV escape/parse/merge logic (see "Backup system" above) and the
  18:00–06:00 window boundary, both pulled into pure functions
  specifically so they're testable without SAF/WorkManager.
- `backup/XlsxFormatTest.kt` — the spreadsheet-import parser (see
  "Spreadsheet import" above): Excel serial-date/time-fraction
  conversion at their valid-range boundaries, shared-string extraction
  (including split rich-text runs and XML entity unescaping),
  header-column matching regardless of column order or extra columns,
  and a realistic sheet (header + real data + a blank formula-only
  template row, mirroring the actual export format this was built
  against) parsing to the correct entries.
- `ui/DateTimeInputTest.kt` + `ui/DateFormatTest.kt` — every manual
  date/time entry format `normalizeDate`/`normalizeTime` accept or
  reject — including inputs only a paste/autofill/Bluetooth keyboard
  could produce past the fields' Number-keyboard hint (negative signs,
  non-ASCII digits, mixed separators, pathologically long strings) — and
  the ISO ↔ DD/MM/YYYY display conversion, including that the two
  round-trip through each other exactly.
- `data/HoursRepositoryTest.kt` — `HoursRepository`'s Start/End logging,
  Manage-screen edits, deletes, the backup-restore merge, and the
  spreadsheet-import merge (never overwrites a locally-logged date;
  recomputes hours/money against current settings rather than trusting
  the file), run against hand-written in-memory fakes of
  `LogEntryDao`/`PaySettingsDao` (implementing the same `@Dao`
  interfaces Room generates from) rather than a real SQLite database.

A few pieces that were originally `private` were changed to `internal`
specifically to make this possible, with no behavior change:
`PayCalculator.grossIncomeTax`/`niHealthTax`, `ManageScreen.kt`'s
`normalizeDate`/`normalizeTime`, and a new `CsvFormat` object
(`backup/CsvFormat.kt`) that `CsvBackup.kt` now delegates its CSV
building/parsing/merging to instead of doing it inline — `CsvBackup`
itself is left as a thin wrapper that only touches
`Context`/`Uri`/`DocumentFile`.

**Not covered**: actual Room queries, actual SAF file reads/writes, and
the Compose UI itself (screen content, taps, navigation) — those would
need Robolectric or an instrumented `androidTest` source set running on
a device/emulator, neither of which this project has set up. The
calculation/parsing layer above is where a silently wrong number would
actually cost the user money, so it's covered first.
