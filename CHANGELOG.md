# Changelog

## v18 — Widget: remove weighted spacer (current, unconfirmed)
- After v17's fix, a widget resized much taller than its content still
  showed the header/button/Today numbers/month title correctly, but the
  entire breakdown *and* backup row were missing with a large empty
  gap — not the clipping v17 fixed (there was clearly room). The one
  genuinely unusual construct in that part of the layout was a
  weight-based spacer (`GlanceModifier.defaultWeight()`) pushing the
  backup row to the bottom of the widget — weighted `LinearLayout`
  children inside RemoteViews are a known trouble spot on some launcher
  hosts. Replaced it with a plain fixed-height spacer (content now just
  flows top-down; extra space collects at the bottom instead of being
  actively filled).
- Marked unconfirmed: not reproducible locally (no device/emulator in
  this environment), so this is the most plausible fix based on what's
  unusual about that section of the layout, not a confirmed root cause.

## v17 — Widget: fix content getting silently clipped
- The month breakdown added in v16 could render below the widget's
  actual visible area and get silently clipped — RemoteViews-based
  widgets don't scroll or shrink content to fit, they just cut it off
  with zero indication. Tightened paddings/spacers/font sizes
  throughout the widget to shrink its real footprint, and raised the
  declared minimum size (`minHeight` 380dp → 440dp) with real safety
  margin for the worst case (overtime split + savings rows both
  showing at once).
- This only helps widgets placed *after* the update — Android doesn't
  retroactively resize a widget already sitting on a home screen when
  the app changes its size requirements. An already-placed widget
  needs to be removed and re-added, or manually dragged taller via its
  resize handles, to pick up the fix.

## v16 — Widget: practically the whole Home screen
- Expanded the home-screen widget from a compact Start/End + net-income
  card to practically the entire Home screen: today's
  Start/End/Hours/Money, the full month breakdown (gross, overtime
  split, tax, NI, pension, net, savings) with its own `‹`/`›` month
  navigation, and a "Back up now" button with the pending-day count —
  everything `MainScreen.kt` has except the scrollable Recent Days
  list, which Android widgets handle worst of anything Compose can do.
- New `ShiftMonthAction.kt` persists which month each widget instance
  is viewing via Glance's built-in `PreferencesGlanceStateDefinition`
  (no extra dependency — it's already the default `stateDefinition` on
  every `GlanceAppWidget`), independently per placed widget, same "no
  future months" guard `MainViewModel.shiftMonth` already has.
- New `BackupNowAction.kt` triggers a backup from the widget the same
  way the Home screen's button does.
- Widget's minimum size grew accordingly (180×110dp → 250×380dp) to
  fit the extra content; it clips rather than scrolls if resized
  smaller than what it's showing, the standard trade-off for a non-list
  Android widget.
- Verified the additional Glance APIs needed (state persistence,
  `RowScope`/`ColumnScope` weighted layout for spread-apart rows,
  `actionParametersOf`) against the real androidx source, same
  standard as the rest of this widget's implementation. Full suite:
  100/100 tests passing — the widget UI itself still isn't unit
  testable, same limitation as the rest of the Compose UI.

## v15 — Savings target + predictable back button
- New **savings target (%)**, set in Settings alongside hourly
  rate/credit points/pension %, same effective-dated behavior (changes
  only apply from today onward). It's a **set-aside-from-net target,
  not a payroll deduction**: the Home screen shows "Savings" and "Left
  to spend" as two new rows under "Net income" (only when the % is
  above 0), but net income itself never changes — same reasoning CSV
  restore already uses for not touching pay settings. Backed by a new
  Room schema version (v2 → v3, `PaySettings.savingsPct`, defaults to
  0.0 so nothing changes for anyone until they set one).
- Both **pension %** and the new **savings %** are now validated with
  an upper bound (must be under 100%) in Settings, in addition to the
  existing non-negative/finite checks.
- **Predictable back button**: the system/gesture back button now
  returns to the Home screen from Manage entries or Settings (via a
  `BackHandler` in `MainActivity.kt`'s `AppRoot`, reusing the same
  `onClose` logic the in-app back arrow already had) instead of
  exiting the app from wherever you happened to be. Pressed again on
  Home itself, it still exits — no handler is registered there, so it
  falls through to the normal platform behavior for a root activity.
- Added `PayCalculatorTest` coverage: savings as a percentage of net,
  and — the important regression — that changing `savingsPct` never
  changes what `net` itself is. Full suite: 100/100 tests passing.

## v14 — Button label: "Start/End Day" → "Start/End Shift"
- Renamed the Start/End button's labels in both places it appears (the
  Home screen's big button and the home-screen widget) from "Start
  Day"/"End Day" to "Start Shift"/"End Shift". No behavior change.

## v13 — Home-screen widget
- New **home-screen widget** (`widget/`, built with Jetpack Glance —
  `androidx.glance:glance-appwidget:1.1.1`): a Start/End button (same
  Start/End logic and colors as the in-app button, mirroring
  `MainViewModel.logNow()`) plus this month's net income — the same
  figure the Home screen's "Net income" row shows (gross minus income
  tax minus National Insurance/health minus pension).
- Stays in sync with the app, not just its own taps: `MainViewModel`
  now refreshes the widget after every function that could change what
  it shows (logging, editing, deleting, saving pay settings, restoring
  from backup, importing a spreadsheet), in addition to the widget
  updating itself immediately after its own button tap.
- No third-party widget library — Glance is Google's own first-party
  Compose-for-widgets toolkit, consistent with the rest of the app's
  AndroidX-only dependency list.
- Verified the full Glance API surface (package paths, `cornerRadius`'s
  Android-12+-only behavior, the bundled default loading layout) against
  the real androidx source before writing any code, since the official
  docs pages didn't render fetchable content in this environment.
- Full suite: 98/98 tests passing — the widget's own UI isn't unit
  testable (same limitation as the rest of the Compose UI), but every
  calculation/logic path it calls into already was.

## v12 — App icon redesigned as a punch card
- The launcher icon's foreground (`ic_launcher_foreground.xml`) no
  longer shows a clock face — it's a row of four punched holes plus a
  couple of printed time-entry lines on the same white card, matching
  the app's actual name. Background color, card shape, and color
  palette all unchanged.

## v11 — Import days from an Excel spreadsheet
- New Settings section, **"Import from spreadsheet"**: pick a `.xlsx`
  file with "date"/"start of day"/"end of day" header columns (matched
  by name, not fixed column letters — any other columns are ignored)
  and any day found there that isn't already logged locally gets added,
  same never-overwrite rule as restoring from a CSV backup. Built
  against a real spreadsheet export with per-day pay/tax columns after
  the three needed ones, which this correctly ignores.
- No third-party spreadsheet library: `.xlsx` is just a zip of XML
  parts, so the new `backup/XlsxFormat.kt` (pure Kotlin, unit-tested)
  and `backup/XlsxImport.kt` (thin `java.util.zip`/SAF wrapper) read
  exactly the two parts needed — one worksheet + shared strings — the
  same "hand-roll it, no extra dependency" approach `CsvFormat.kt`
  already used for the CSV backup format.
- Pay/tax settings are never read from the spreadsheet (same reasoning
  as CSV restore) — the app's current Settings apply to every imported
  day.
- Verified against the real spreadsheet this was built against (a
  9-day sample) before writing it up, in addition to the new unit
  tests: `backup/XlsxFormatTest.kt` (18 tests) and two new
  `HoursRepositoryTest` cases for the import-merge behavior. Full
  suite: 98/98 tests passing.

## v10 — Renamed to PunchCard
- Renamed the project end-to-end: top-level folder `hour log app` →
  `PunchCard`, Kotlin package/applicationId `com.hourslog.app` →
  `com.punchcard.app`, `Application` subclass `HoursLogApp` →
  `PunchCardApp`, theme `Theme.HoursLog` → `Theme.PunchCard`/
  `HoursLogTheme` → `PunchCardTheme`, `rootProject.name` `HoursLog` →
  `PunchCard`, on-phone app label and Home-screen header "Hours Log" →
  "PunchCard".
- Because the applicationId changed, this is a new app identity as far
  as Android is concerned: any copy already installed on a phone needs
  a full uninstall + reinstall, and the backup-folder permission
  granted in Settings needs to be re-picked (it was granted to the old
  package). Nothing else about behavior or data format changed.
- Verified with a full rebuild + test run after the rename: all 88
  tests still pass under the new package.
- Initialized the project as a git repository for the first time.

## v9 — NaN/Infinity input hardening, malformed-date display fix
- **Fixed a real display bug**: `formatDateDisplay`'s "fall back to the
  raw string on malformed input" guard only checked that the string had
  3 hyphen-separated parts — but so does any garbage string like
  `"not-a-date"` (`["not", "a", "date"]`), so it silently reformatted
  into nonsense (`"date/a/not"`) instead of showing the raw value. Now
  validated against a strict `\d{4}-\d{2}-\d{2}` pattern before
  reformatting. Caught by the existing `DateFormatTest`.
- **Fixed a real calculation-corruption bug**: the Settings screen's
  hourly rate / credit points / pension % fields parsed input with
  `toDoubleOrNull()` and only checked `<= 0` / `< 0`. Typing `NaN` (or
  pasting it — the "Number" keyboard hint doesn't block a paste or a
  Bluetooth keyboard) parses successfully, and `NaN <= 0` is `false` in
  Kotlin/Java, so it sailed straight past validation and got saved —
  silently poisoning every pay calculation from that point on
  (`hours * NaN = NaN`, propagating through gross/tax/net with no error
  shown anywhere). `Infinity` had the same gap. Fixed by adding
  `.isFinite()` checks to all three fields (`SettingsScreen.kt`).
- Same class of bug existed on the restore path: `CsvBackup.kt` parsed
  the Hours/Money columns of a (possibly hand-edited, per `SETUP.md`'s
  own suggestion, or corrupted) backup CSV with `toDoubleOrNull()` with
  no finiteness check, so a stray `NaN`/`Infinity` in a CSV cell would
  restore straight into the database and corrupt month totals. Now
  discarded (falls back to `null`, same as any other unparseable value)
  if not finite.
- Added `PayCalculatorTest` coverage for the previously-untested top
  three income tax brackets (35%/47%/50%), the exact 10-hour
  overtime-tier boundary, and the `settingsForDate` fallback-to-earliest
  path used when a logged date predates every known pay-settings row —
  all passed once written, no bugs found there.
- Added 12 regression tests to `DateTimeInputTest` for
  `normalizeDate`/`normalizeTime` against inputs a paste, autofill, or
  Bluetooth keyboard could produce despite the field's Number-keyboard
  hint: negative signs, mixed/extra separators, letters mixed into
  punctuated input, whitespace-only, non-ASCII (Arabic-Indic) digits,
  and pathologically long strings. All already degraded safely to "show
  a validation error" rather than crashing or misparsing — confirmed,
  not fixed.
- **Start/End button**: removed the 🌅/🌇 emoji and renamed the labels
  from "Log Start"/"Log End" to "Start Day"/"End Day" (`MainScreen.kt`).
- Full suite: 88/88 tests passing (`./gradlew test`).

## v8 — Full unit test suite
- Added a real JUnit test suite covering every calculation and parsing
  rule in the app, runnable from Android Studio or `./gradlew test` with
  no emulator/phone needed: `logic/PayCalculatorTest.kt` (hours/break
  math, overtime tiers, income tax brackets, NI + health thresholds and
  ceiling, the credit-points tax floor, full month summaries),
  `backup/CsvFormatTest.kt` (CSV escaping/parsing, upsert-by-key
  merging, the older-schema-header regression from v6),
  `backup/BackupWorkerLogicTest.kt` (the 18:00–06:00 window boundary),
  `ui/DateTimeInputTest.kt` (every manual date/time entry format),
  `ui/DateFormatTest.kt` (ISO ↔ DD/MM/YYYY display), and
  `data/HoursRepositoryTest.kt` (Start/End logging, edits, deletes,
  backup-restore merging — against in-memory fake DAOs, no real
  database needed).
- To make the above possible, three previously-`private` pieces of pure
  calculation/parsing logic were pulled out into their own testable
  units, with no behavior change: `CsvBackup.kt`'s CSV
  escape/parse/merge logic moved into a new `backup/CsvFormat.kt`;
  `ManageScreen.kt`'s `normalizeDate`/`normalizeTime` changed from
  `private` to `internal`; `BackupWorker`'s night-window check split
  into a pure `isNightWindowForHour(hour: Int)`. `PayCalculator.kt`'s
  `grossIncomeTax`/`niHealthTax` were also changed from `private` to
  `internal` so their bracket/threshold math can be tested directly,
  not just indirectly through a full month summary.
- See `SETUP.md`'s "Running the unit tests" section for what's covered
  and what still isn't (Room queries, actual SAF file I/O, and the
  Compose UI itself would need instrumented/`androidTest` or Robolectric
  tests, which this project doesn't have set up yet).

## v7 — DD/MM/YYYY dates, numeric-keypad entry, duplicate-day confirm
- **Manual date/time entry works on the numeric keypad**: the Manage
  screen's Date/Start/End fields use a Number-type keyboard, which on
  Android has no `:`, `-`, or `/` key at all — typing `09:00` or
  `17/08/2026` was previously impossible. Both fields now also accept
  plain digits (`0900` for a time, `17082026` or `170826`-style digits
  for a date) and normalize them to the correct stored format.
- **Dates display as DD/MM/YYYY everywhere** in the UI (Manage screen
  rows and dialogs, Recent list, Today card). Internal storage — Room
  keys, CSV backup files, and every date-range/effective-date comparison
  — stays ISO `YYYY-MM-DD`, since that's the format that sorts and
  compares correctly as plain text; only the on-screen formatting
  changed (`DateFormat.kt`).
- **Duplicate-day warning when adding a day**: "Add day" on the Manage
  screen used to silently overwrite an existing entry if you typed a
  date that was already logged. It now checks first and, if that date
  already has an entry, shows a confirmation popup with the existing
  entry's time/hours before overwriting — Cancel backs out with nothing
  saved, Overwrite proceeds. Editing an existing row (tap on a row) is
  unaffected, since there's no "new date" to collide with there.
- **Fixed a confusing month breakdown**: once a month has overtime,
  "Regular pay" only ever showed the capped-at-8h/day hours (e.g.
  68.33h out of 74.51h actually worked) with no total-hours figure
  anywhere on the card, making it look like hours had gone missing.
  Added a "Hours logged" row showing the real month total above the
  Regular/Overtime split. The pay math itself was never wrong — the
  regular/overtime split by design excludes overtime hours from
  "Regular pay" — it just wasn't labeled clearly.

## v6 — Overtime pay + restore-from-backup
- **Overtime calculations**: hours beyond 8 in a single day now pay
  125% (hours 9–10) and 150% (hour 11+), factored into both a day's
  Money figure and the month's Gross pay. Shown as its own "Regular
  pay" / "Overtime pay" line items in the month breakdown (not just
  folded into Gross) whenever a month has any overtime hours. Toggleable
  per-settings (default on) for overtime-exempt roles. Required a Room
  schema migration (`pay_settings.overtimeEnabled`, v1 → v2).
- **Restore from backup**: a new "Restore from backup folder" button in
  Settings reads every `HoursBackup-*.csv` in a chosen folder and fills
  in any day missing from the local database — recovers logged days
  after an app uninstall/reinstall (or a fresh install on a new phone),
  without ever overwriting a day already logged locally. Pay/tax
  settings aren't in the CSVs, so those still need re-entering.
- Also fixed a latent missing-import bug in `SettingsScreen.kt`
  (`Modifier.width` used without importing it) caught while making
  these changes — harmless until the project is actually compiled for
  the first time, which hasn't happened yet in this sandbox.

## v5 — Native rewrite
Rebuilt from scratch as a fully native Android app (Kotlin + Jetpack
Compose + Room + WorkManager), replacing the PWA + Google Sheets/Apps
Script backend entirely. Runs 100% on-device with no `INTERNET`
permission; the nightly backup writes a CSV into a user-chosen folder
via the Storage Access Framework instead of calling the Drive/Sheets
API. Iterated in several steps within the native project:
- Start/End button switching made purely data-driven (End only reachable
  after Start is logged that day) instead of clock-based, and "today"
  now rolls over automatically at midnight even if the app is left open.
- Added a **Manage entries** screen: browse any month, edit or delete
  any day's start/end time, or add a day retroactively for any past
  date — not just the month being viewed.
- Fixed invisible (white-on-white) input text in the Pay & tax settings
  fields, caused by the app's dark color scheme's default text color
  leaking into white input fields.
- Confirmed/hardened the "Back up now" button to ignore both the
  18:00–06:00 window and the wifi check (it already did, via a forced
  WorkManager request with no network constraint).

## v4 — PWA: fixed breaks + nightly Drive backup
- Three fixed daily breaks (10:00–10:10, 12:40–13:20, 15:00–15:10)
  auto-subtracted from worked hours.
- Added a `Money` (gross pay) field computed per day.
- Nightly, wifi-gated backup of completed days into a separate per-month
  Google Sheet in Drive (distinct from the live working sheet), plus a
  full month-end summary once the month's last day was logged. Gated on
  local time (18:00–06:00) and `navigator.connection.type === 'wifi'`,
  with a manual "Back up now" bypass.

## v3 — Israeli net-income estimate
Added income tax (progressive brackets), National Insurance + health
tax, and a 6% pension deduction, computed from an hourly rate and tax
credit points. Pay settings made effective-dated (editable, but never
retroactive — a rate change only applies from the day it's saved
onward).

## v2 — Start/End buttons
Replaced the single date+hours form with two states on one button,
originally switched by clock time (00:00–11:59 = Start, 12:00–23:59 =
End) — later replaced in v5 with data-driven switching. Date and time
were auto-captured from the device.

## v1 — Initial PWA
A phone-installable Progressive Web App with a manual date + hours-
worked form, syncing to a Google Sheet via a Google Apps Script Web App
backend.
