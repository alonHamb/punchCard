# PunchCard

A fully native Android app (Kotlin + Jetpack Compose) for logging work
hours, estimating take-home pay for an Israeli salaried employee, and
backing up to a folder of your choice — all running 100% on-device, with
no server, no account, and no `INTERNET` permission in the manifest at
all. The only thing that ever touches storage outside the phone is the
backup file, and even that goes out indirectly through Android's Storage
Access Framework (e.g. into a folder your Google Drive app syncs) — the
app itself never opens a network connection.

This project started as a Progressive Web App backed by a Google Sheet
and evolved, over several iterations, into this native rewrite. See
[`CHANGELOG.md`](CHANGELOG.md) for the full history.

## Features

- **One-tap Start/End logging.** A single button shows "Start Day" until
  you tap it, then switches to "End Day" — End only ever becomes
  reachable after Start has actually been logged for that day, and every
  new calendar day resets back to Start automatically, even if the app
  was left open across midnight.
- **Automatic break deduction.** Three fixed daily breaks (10:00–10:10,
  12:40–13:20, 15:00–15:10) are subtracted from worked hours whenever a
  shift overlaps them.
- **Israeli net-income estimate.** Income tax (progressive brackets),
  National Insurance + health tax, and a pension deduction are computed
  from your hourly rate and tax credit points. This is an **estimate,
  not payroll or tax advice** — see the caveats in
  [`ARCHITECTURE.md`](ARCHITECTURE.md#pay-calculator).
- **Overtime pay.** Hours beyond 8 in a single day are automatically
  paid at 125% (hours 9–10) and 150% (hour 11+), toggleable per-settings
  in case you're in an overtime-exempt role. Included in both the daily
  Money figure and the month's Gross pay.
- **Effective-dated pay settings.** Changing your hourly rate, credit
  points, or pension % only ever affects today onward — past months keep
  using whatever was in effect at the time, so they never silently
  change.
- **Manage entries screen.** Browse any month, edit or delete any day's
  start/end time, or add a day you forgot to log entirely — for any
  date, past or present.
- **Local-first backup, no cloud account needed.** A real background job
  (WorkManager — runs even while the app is closed) checks every ~15
  minutes whether it's between 18:00–06:00 and the phone is on wifi; if
  so, it writes any completed days into that month's CSV file in a
  folder you picked once in Settings, plus a full month summary once the
  month's last day is logged. A **"Back up now"** button forces an
  immediate backup regardless of the time or network — useful right
  after adding or fixing an entry.
- **Survives an uninstall/reinstall.** Since all data lives in a local
  database that Android wipes along with the app, Settings has a
  **"Restore from backup folder"** button: point it at the folder your
  old backups are in and it reads every day back out of those CSV files
  and fills in whatever's missing locally — without ever overwriting a
  day you've already re-logged. (Pay/tax settings aren't in the CSVs, so
  those need a quick re-entry after restoring.)
- **Import days from an Excel spreadsheet.** Settings has an "Import from
  spreadsheet" button that reads a `.xlsx` file with a header row
  containing "date", "start of day", and "end of day" columns (any other
  columns — formulas, pay figures, whatever — are ignored) and adds any
  day it finds that isn't already logged locally, same never-overwrite
  rule as restoring from backup. Handy for bringing in a spreadsheet you
  were already keeping by hand before switching to this app.

## Screens

| Screen | What it's for |
|---|---|
| **Home** | Today's Start/End button, today's numbers, this month's pay breakdown, recent days, backup status. |
| **Manage entries** | Browse any month; tap a day to edit its times or delete it; add a day retroactively. |
| **Settings** | Hourly rate / tax credit points / pension %, the backup folder picker, and spreadsheet import. |

## Project structure

```
app/src/main/java/com/punchcard/app/
├── data/       Room entities, DAOs, and the repository (single source of truth for the DB)
├── logic/      PayCalculator — pure Kotlin, no Android deps, unit-tested
├── backup/     WorkManager job, SAF CSV writer/reader, scheduling, folder
│               preference, and the .xlsx spreadsheet-import parser
└── ui/         Compose screens and the ViewModel
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for a full technical walkthrough
of the data model, calculation logic, and backup design.

## Building and running it

See [`SETUP.md`](SETUP.md) — open the project in Android Studio, let
Gradle sync, then Run. No signing setup or Play Store account needed;
it installs straight onto a phone over USB or wireless debugging.

## Privacy

- No `INTERNET` permission — the manifest literally does not request it.
- No account, no login, no analytics, no ads.
- All data (hours, pay settings) lives in a local SQLite database on the
  phone (via Room) and never leaves it except through the backup file
  you explicitly opt into and point at a folder of your choosing.
- `ACCESS_NETWORK_STATE` is the only permission requested, used solely
  to check whether the connection is wifi before writing a backup file.

## Disclaimer

The tax/National Insurance/pension figures are a simplified estimate
using hardcoded 2026 constants (see `PayCalculator.kt`) and do not model
every real-world nuance (the ~35% pension tax credit mechanism, marginal
relief, multiple employers, non-resident status, etc). Verify against an
actual payslip or a professional for anything that matters financially.
