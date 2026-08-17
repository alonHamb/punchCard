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
)
```
`overtimeEnabled` was added in schema v2 (`AppDatabase.MIGRATION_1_2`,
a plain `ALTER TABLE ... ADD COLUMN ... DEFAULT 1`) — existing rows
default to enabled.
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
shows "End Day" if and only if today's `LogEntry.startTime` is set
(`HoursRepository.hasStartedToday`/`logNext` re-read the DB fresh on
every tap, so it's race-safe against stale UI state).

Three screens, switched via a simple `Screen` enum in `MainActivity.kt`
(`AppRoot` composable) — no navigation library, since there are only
three destinations and none of them need deep linking or a back stack
beyond a single "close" callback.

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
  date that predates all of them, and full month summaries (a mid-month
  rate change, an in-progress day with no hours yet, the empty-entries
  case).
- `backup/CsvFormatTest.kt` + `backup/BackupWorkerLogicTest.kt` — the
  CSV escape/parse/merge logic (see "Backup system" above) and the
  18:00–06:00 window boundary, both pulled into pure functions
  specifically so they're testable without SAF/WorkManager.
- `ui/DateTimeInputTest.kt` + `ui/DateFormatTest.kt` — every manual
  date/time entry format `normalizeDate`/`normalizeTime` accept or
  reject — including inputs only a paste/autofill/Bluetooth keyboard
  could produce past the fields' Number-keyboard hint (negative signs,
  non-ASCII digits, mixed separators, pathologically long strings) — and
  the ISO ↔ DD/MM/YYYY display conversion, including that the two
  round-trip through each other exactly.
- `data/HoursRepositoryTest.kt` — `HoursRepository`'s Start/End logging,
  Manage-screen edits, deletes, and the backup-restore merge, run
  against hand-written in-memory fakes of `LogEntryDao`/
  `PaySettingsDao` (implementing the same `@Dao` interfaces Room
  generates from) rather than a real SQLite database.

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
