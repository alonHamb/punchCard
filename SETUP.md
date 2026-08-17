# PunchCard — Native Android App Setup

This is a real, fully native Android app (Kotlin + Jetpack Compose) —
not a website. Everything runs 100% on your phone: hours, breaks, the
income estimate, all of it lives in a local on-device database. The
**only** thing that ever touches a network is the nightly backup, and
even that is indirect: the app writes a CSV file into a folder you
choose (e.g. one your Google Drive app syncs) through Android's
Storage Access Framework — it never opens an internet connection
itself. There is no `INTERNET` permission in the manifest at all.

## What it does

- One big button that swaps automatically: it shows **Start Day**
  until you tap it, then switches to **End Day** — End is only ever
  reachable after Start has been logged for that day, there's no clock
  cutoff. Every new calendar day resets back to Start Day automatically
  (even if the app's been left open overnight). Tapping records the
  current date/time from your phone — nothing to type. Tapping again
  after both are set just corrects that day's time (handy for a mis-tap).
- Three fixed breaks are automatically subtracted from worked hours
  whenever a shift overlaps them: **10:00–10:10**, **12:40–13:20**,
  **15:00–15:10**.
- Estimated net income (Israel, salaried employee/sachir): income tax,
  National Insurance + health tax, a pension deduction, and overtime pay
  (125% after 8h/day, 150% after 10h/day, toggleable in Settings) —
  computed from your hourly rate and tax credit points, set once in
  Settings; changes only apply from that day onward so past months
  never shift.
- A real background job (WorkManager) checks every ~15 minutes whether
  it's between **18:00–06:00** and you're **on wifi**; when both are
  true, it writes any completed days into that month's backup CSV, and
  — on the last day of the month — the full income calculation too.
  This runs even if the app is closed, which a website never could.
- A **"Back up now"** button on the main screen bypasses the time/wifi
  check for whenever you want to force it.
- A **Manage entries** screen (the calendar icon next to Settings) lets
  you browse any month and edit or delete any day's start/end time, or
  add a day you forgot to log entirely. Editing recalculates that day's
  hours/money automatically and always queues it for the next backup —
  even a day from a past month that was already backed up before.
- A **"Restore from backup folder"** button in Settings reads your old
  backup CSVs back in and refills the local database with anything
  missing — this is what lets your logged days survive an uninstall/
  reinstall (or moving to a new phone), since the app's own local
  database gets wiped along with the app but the backup files, sitting
  outside app storage, don't.

## 1. Open the project

1. Open **Android Studio**.
2. **File → Open**, and select this folder (the one this file is in).
3. Android Studio will show a prompt about trusting/syncing the Gradle
   project — click **Trust Project** and let Gradle sync run. This can
   take a few minutes the first time (it downloads dependencies).
4. If Android Studio asks to install a missing SDK platform (this
   project targets **Android 15 / API 35**) or to upgrade the Gradle
   plugin, accept — those are normal one-time prompts, not errors.

## 2. Build

- **Build → Make Project** (or the hammer icon) compiles everything.
- **Run → Run 'app'** (green ▶ icon) builds and installs it.

## 3. Run it on your phone

1. On your phone: **Settings → About phone**, tap "Build number" 7
   times to unlock Developer Options, then **Settings → Developer
   options → USB debugging** → on.
2. Connect your phone to your PC with a USB cable; approve the "Allow
   USB debugging?" prompt on the phone.
3. In Android Studio's toolbar, pick your phone from the device
   dropdown (next to the Run button), then click **Run ▶**.
4. The app installs and opens automatically. From then on it's on your
   home screen like any other app — no cable needed unless you're
   pushing a new build.

(Wireless debugging works too, if you'd rather not use a cable —
**Developer options → Wireless debugging** on the phone, then **Pair
using Wi-Fi pairing code** from Android Studio's device dropdown.)

## 4. Choose a backup folder

Open the app → ⚙️ Settings → **Backup folder → Choose folder**. This
opens Android's normal folder picker. If you have the **Google Drive**
app installed and signed in, "Google Drive" appears as a location in
that picker — navigate into it and pick or create a folder there
(e.g. a "PunchCard Backups" folder). Anything the app writes there,
Drive uploads on its own, under its own permissions — this app never
sees your Google account or makes a network call.

If you'd rather not use Drive, any folder works (e.g. a folder synced
by another app, or just local storage you move files out of by hand).

## 5. Set pay settings

Same screen, **Pay & tax settings**: hourly rate, tax credit points
(2.25 for a single Israeli resident with no dependents, 2.75 for
women — more with children/marriage), and pension % (defaults to 6).
Tap **Save pay settings** — this is stored with today's date as its
"effective from" date, so changing it later never rewrites past
months' numbers.

## 6. Keep background backup reliable

Some phones (especially Xiaomi/Huawei/Samsung with aggressive battery
managers) kill background work for apps unless told not to. For the
nightly backup to actually fire reliably:

- **Settings → Apps → PunchCard → Battery → Unrestricted** (wording
  varies by phone/Android version — look for "no restrictions" or
  disable "battery optimization" for this app specifically).

Without this, the OS may just never wake the app at night — the app
still works fine for logging hours either way, this only affects the
automatic 18:00–06:00 backup check.

## Running the unit tests (optional, no phone needed)

Every calculation and parsing rule in the app has a real JUnit test
suite that runs instantly on your PC — no emulator or phone required,
because none of it touches Android/Room/SAF directly (the pieces that
do — reading/writing actual files, the actual database — are kept as
thin wrappers around pure Kotlin logic, so the logic itself is fully
testable):

- **Run all of them at once**: right-click the `app/src/test` folder in
  Android Studio's project tree → **Run 'Tests in ...'**, or run
  `./gradlew test` from a terminal. A green checkmark on every file
  means all pass; a red one jumps you straight to the failing
  assertion.
- Or run a single file/test: right-click it → **Run**, or use the green
  gutter arrows next to any individual `@Test`.

What's covered, one file per area:

- `logic/PayCalculatorTest.kt` — hours-worked math (break subtraction,
  midnight-crossing shifts, a shift starting mid-break), overtime tiers
  (125%/150%, toggled on/off, including the exact 10-hour boundary),
  income tax bracket boundaries across all seven brackets (10% through
  the top 50%), National Insurance + health tax (threshold and
  ceiling), the credit-points floor that keeps tax from going negative,
  the settings-fallback-to-earliest path for dates that predate every
  known pay-settings row, and full month summaries (including a
  mid-month rate change and days with no hours yet).
- `backup/CsvFormatTest.kt` — CSV escaping/parsing (commas, quotes,
  newlines), and the upsert-by-key merge that backup writes rely on,
  including the older-schema-header regression that was a real bug once
  (see CHANGELOG v6).
- `backup/BackupWorkerLogicTest.kt` — the 18:00–06:00 backup-window
  boundary hours.
- `ui/DateTimeInputTest.kt` — every accepted/rejected manual date/time
  entry format (punctuated and digit-only, calendar-invalid dates, leap
  years, malformed input), plus everything a paste/autofill/Bluetooth
  keyboard could put into these Number-hinted fields that on-device
  typing couldn't: negative signs, mixed separators, letters mixed into
  punctuated input, non-ASCII digits, whitespace-only, and
  pathologically long strings — all confirmed to degrade to a validation
  error rather than crash or misparse (see CHANGELOG v9).
- `ui/DateFormatTest.kt` — the ISO ↔ DD/MM/YYYY display conversion,
  including that it round-trips exactly back through `normalizeDate`.
- `data/HoursRepositoryTest.kt` — Start/End logging, Manage-screen
  edits, deletes, and the backup-restore merge (fills in missing days,
  never overwrites what's already logged locally) — tested against
  in-memory fake DAOs, so no real database is involved.

**Not covered by these (would need an emulator/device, i.e. Android
Studio's `androidTest` source set, which this project doesn't have
yet)**: actually reading/writing files through the Storage Access
Framework, actually querying Room, and anything about the Compose UI
itself (what's on screen, taps, navigation). Those would need
Robolectric or instrumented tests — ask if you want that layer added
too; the current suite deliberately covers every calculation and parsing
rule instead, since that's where a wrong number would actually hurt.

## Notes / limitations

- **This is an estimate, not payroll or tax advice.** Simplified 2026
  Israeli tax brackets, National Insurance/health rates, and credit
  point value are hardcoded in `PayCalculator.kt` — it doesn't model
  the ~35% pension tax credit mechanism, marginal relief, multiple
  employers, etc. Check a real payslip for anything that matters
  financially. If rates change in a future year, the constants at the
  top of `PayCalculator.kt` are where to update them.
- The break windows (10:00–10:10, 12:40–13:20, 15:00–15:10) are
  hardcoded in `PayCalculator.BREAK_WINDOWS` — easy to change there if
  your schedule changes.
- All dates/times come from the phone's own clock — there's no server
  involved anywhere.
- Backups are idempotent: re-running one (e.g. via "Back up now" more
  than once) just overwrites that day's/month's row rather than
  duplicating it.
- Deleting a day in Manage entries removes it from the on-device
  database, but if that day was already backed up, its old row stays in
  that month's CSV file in your backup folder (deletes aren't pushed
  into already-written backup files). Edit the CSV by hand if you need
  it fully gone from there too.
