# Manual test checklist

Run these before each release (spec §11). They cover what JVM tests can't: the merged manifest, the device's
backup service, timing on a phone, real spreadsheets and the signed APK. Use the emulator (AVD
`Medium_Phone_API_37.0`) unless a step names a real phone. Gradle commands run from `android/` with
`JAVA_HOME` set to JDK 21; `adb` is in the SDK's `platform-tools`. In Git Bash, set `MSYS_NO_PATHCONV=1`
before `adb shell` commands with paths.

The real debug and release builds share the application ID `io.github.codex07.iou` but use different signing
keys. Use separate fresh, disposable test images for debug checks and signed-release checks. Never uninstall,
clear, restore or change the signing identity of an app profile containing user data. Any destructive backup
or restore check must use task-generated synthetic data on a disposable image. Record the initial backup
service state and transport, and restore both after testing.

## 1. Merged manifest (spec §9, §14 item 4)

- [x] Build the release APK and list its permissions:

  ```bash
  ./gradlew assembleRealRelease
  "$ANDROID_HOME/build-tools/<latest>/aapt2" dump permissions app/build/outputs/apk/real/release/app-real-release-unsigned.apk
  ```

  Expected, and nothing else:
  - `INTERNET` (declared by the real flavor);
  - `POST_NOTIFICATIONS` (declared by the app for recurring-expense notifications, M2);
  - `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (WorkManager);
  - `io.github.codex07.iou.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature permission that
    `androidx.core` declares for the app's own receivers;
  - `USE_BIOMETRIC` (declared by the app and by `androidx.biometric`) and `USE_FINGERPRINT` (added only by
    `androidx.biometric` 1.1.0). Neither is a financial-data permission; confirm both appear and nothing else is new.

  `FOREGROUND_SERVICE` must be absent; `src/real/AndroidManifest.xml` removes it. For anything new, find
  its source in `app/build/outputs/logs/manifest-merger-real-release-report.txt`. Remove it with
  `tools:node="remove"` if the app doesn't need it.
- [x] In `app/build/intermediates/merged_manifest/realRelease/processRealReleaseMainManifest/AndroidManifest.xml`,
  the exported components are `MainActivity`, WorkManager's `SystemJobService` (`BIND_JOB_SERVICE`), and
  `DiagnosticsReceiver` and `ProfileInstallReceiver` (both `DUMP`). There is no `KdfTimingReceiver`; it is
  realDebug only.
- [x] The application has `allowBackup="true"`, `fullBackupContent="@xml/backup_rules"`,
  `dataExtractionRules="@xml/data_extraction_rules"` and `usesCleartextTraffic="false"`.

Result (2026-11-22, the signed `iou-v0.2.1.apk` from the GitHub release, checked with `aapt2 dump permissions` and
`aapt2 dump xmltree`): exactly the permissions above, no `FOREGROUND_SERVICE`; the only exported components are the
four listed, and there is no `KdfTimingReceiver`; the application attributes are as listed.

## 2. App lock and screen protection (spec §9)

The UI owns the lock screen and the Settings controls. The core owns the policy (`TimedLockGate`) and `FLAG_SECURE`
(`AppSecurity`); the UI never sets window flags. Use a fresh disposable image containing only test data, with a
secure Android screen lock set.

- [ ] Settings → App lock: turn it on. The system prompt appears first; after you authenticate the switch is on and
  the open app stays unlocked.
- [ ] Settings → Saved timeout: the row and the picker describe locking after the app has been in the background
  for the chosen time, and "Immediately" locking on every return. Pick each of Immediately, 1, 5 and 15 minutes in
  turn and check:
  - return within the timeout: no unlock screen;
  - return after the timeout (wait it out): the unlock screen covers everything, and **Unlock** shows the system
    prompt;
  - Immediately: every return shows the unlock screen.
- [ ] Cold start (force-stop, then launch) with app lock on: the unlock screen appears. Cancelling the prompt keeps
  the content covered and **Unlock** can retry.
- [ ] Rotate during the system prompt, and return from a document picker (backup/restore/import): no spurious
  lock inside the timeout, and no stuck prompt.
- [ ] Settings → Screen protection on: screenshots are refused, the Recents thumbnail is blank, and
  `adb shell dumpsys window windows | grep SECURE` finds the window. Off: screenshots work and the flag is gone.
  Check the first frame of a cold start and the Recents thumbnail in both states.
- [ ] Turn app lock off (needs an unlocked app): no unlock screen on return or cold start.
- [ ] Data management lifecycles (real flavor, synthetic data on a disposable AVD): restore a backup and watch the
  screen close; the snackbar then shows the restore outcome ("Restore complete..."), not only the generic
  "ledger was replaced" text. Kill the app during a pre-commit restore (for example with a gated test build or an
  early `adb shell am force-stop`), relaunch and unlock: the original ledger is intact and one dialog says
  "The last restore was interrupted. Nothing changed." After **Done**, relaunch: no dialog. A normal launch and a
  completed restore show no such dialog. Open an import preview, press Back and discard: no import session stays open.

## 3. Auto Backup (spec §9, §14 item 5)

The strict rules allow cloud backup only when the transport provides the required client-side encryption.
An unsuccessful `bmgr` result alone does not prove that policy worked: confirm the APK's rule resource and use
the local-only A/B check below. On the Android 37 image checked on 2026-11-05, the strict attempt returned
`Transport rejected package` (`-1002`), rather than the older plan's literal `Backup is not allowed`.

- [ ] Use a new disposable test image with task-generated synthetic ledger data. Before changing backup
  settings, record `adb shell bmgr enabled` and the current transport from `adb shell bmgr list transports`
  (marked `*`). Start realDebug, then press Home and run:

  ```bash
  adb shell bmgr enable true
  adb shell bmgr transport com.android.localtransport/.LocalTransport
  adb shell bmgr backupnow io.github.codex07.iou
  ```

  Confirm the strict APK's `data_extraction_rules` resource has
  `disableIfNoEncryptionCapabilities="true"` with
  `"$ANDROID_HOME/build-tools/<latest>/aapt2" dump xmltree app/build/outputs/apk/real/debug/app-real-debug.apk --file res/xml/data_extraction_rules.xml`.
  Record the actual result and check
  the backup log. The Android 37 result above was corroborated by a local-only A/B build: with only that
  attribute removed, the same package and transport backed up successfully (185,344 bytes); `aapt2` dumps
  confirmed the attribute was the only policy difference. A transport error by itself is not a pass.
- [ ] Check backup contents with a local build only, never committed, on the disposable image and synthetic
  data: save the approved XML and its hash,
  remove `disableIfNoEncryptionCapabilities="true"` from `data_extraction_rules.xml`, build and install,
  start the app, then press Home. Confirm with `aapt2` that only that attribute differs from the strict APK.
  Put a stray `restore-marker.json` and `files/other.txt` next to the ledger:

  ```bash
  adb shell "run-as io.github.codex07.iou sh -c 'echo {} > databases/restore-marker.json; echo x > files/other.txt'"
  adb shell bmgr backupnow io.github.codex07.iou      # expected: Success
  adb shell pm clear io.github.codex07.iou
  adb shell bmgr restore 1 io.github.codex07.iou
  adb shell "run-as io.github.codex07.iou ls -R ."
  ```

  Only `databases/` comes back: the `iou-*.db` files and `db-state.json`, without `restore-marker.json`.
  `files/` and `no_backup/` are empty. The next start shows the ledger and creates a new `device-id`; device
  settings start at their defaults. `pm clear` in this step is restricted to this task-generated synthetic
  app data; never run it against an existing user profile.
- [ ] Restore the exact approved XML bytes and verify their saved hash. Rebuild and reinstall the strict APK,
  then restore the recorded state: `bmgr transport <the noted original transport>` and
  `bmgr enable <the recorded enabled state>`.

## 4. Argon2id on the device (spec §14 item 3)

- [ ] With realDebug installed:

  ```bash
  adb shell am broadcast -n io.github.codex07.iou/.debug.KdfTimingReceiver
  adb logcat -d -s IouKdf
  ```

  Run it three times; the first run includes warm-up. On the isolated Android 37 emulator checked on
  2026-11-05, encryption/decryption times were 784/816 ms, 658/636 ms and 641/623 ms. These are emulator
  measurements; record the current device's actual timings rather than reusing them.
- [ ] **On the slowest real phone available:** run the same broadcast. If an encryption takes more than
  about 2 s after warm-up, lower `KdfParams.DEFAULT.memoryKiB`. Backups record their parameters in the
  header, so older backups still open.

## 5. Frankfurter (spec §14 item 2)

- [x] `curl "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=SEK%2CUSD"` answers 200 with JSON-number
  rates and the latest working day as `date`.
- [x] `curl "https://api.frankfurter.dev/v1/2026-11-01?base=EUR&symbols=SEK"` (a Sunday) answers with
  `"date":"2026-10-30"`.
- [x] A future date (`/v1/2099-01-01?base=EUR&symbols=SEK`) answers 404 `{"message":"not found"}`. The app
  treats any answer except 200, and any timeout, as unavailable (spec §4.5).

Result (2026-11-22): all three as expected (`latest` dated Friday 2026-11-20, the Sunday query dated 2026-10-30,
the future date 404).

## 6. Exports in spreadsheets (spec §7.2)

- [ ] Run `./gradlew testFakeDebugUnitTest --tests "*ExportSamplesTest"`. It writes `normalized.zip`,
  `normalized-excel.zip`, `report.csv` and `report-excel.csv` to `app/build/export-samples/`.
- [ ] Open each CSV, and `people.csv` from each zip, in Excel and in LibreOffice Calc:
  - import plain files as comma-separated UTF-8 and Excel-friendly files as UTF-8 with BOM and `;` delimiters;
    confirm columns split correctly in both apps. Excel installations whose default separator is comma may
    mis-split a semicolon CSV when opened directly; use **Data > From Text/CSV** and select semicolon;
  - the person `=HYPERLINK("http://evil","x")` shows as text, not as a link or a formula. The leading `'` is
    the reversible escape; Excel may hide it;
  - amounts read as plain decimals.

  In the 2026-11-05 native checks, explicit imports in Excel 16 and LibreOffice Calc 26.8 split the columns
  correctly, kept amounts numeric, and showed no formulas or hyperlinks. Excel 16's default open path did
  not split the semicolon files correctly; use the explicit import procedure above. These results cover
  those versions and the tested files.
- [ ] XLSX (spec §7.3, AC M2-15): `ExportSamplesTest` also writes `report.xlsx` and `normalized.xlsx`. Open both in
  Excel and in LibreOffice Calc: they open without a repair prompt; dates are real dates (sortable, shown as
  `yyyy-mm-dd`); amounts are numbers with the currency's decimals; `Summary`, `Report` and one sheet per group
  exist; the person `=HYPERLINK("http://evil","x")` shows as text; the normalized workbook has one sheet per table
  with numeric `*_minor` columns.
- [ ] On a real-flavor build (Settings > Backup & data management), with synthetic data only:
  - Create backup with encryption off: the warning and its acknowledgement are required; the picker
    suggests `iou-backup.json`; the result says the backup was saved. Pulled file starts with `{` and holds
    the ledger.
  - Create backup with encryption on: the picker suggests `iou-backup.ioub`; the pulled file starts with
    `IOUB`.
  - Export normalized CSV: the picker suggests `iou-export.zip`; the file is a ZIP with `manifest.json`.
    Export a report: `iou-export.csv`. The Format field offers CSV and XLSX; XLSX saves `.xlsx` workbooks in both shapes.
  - Restore a backup: the confirmation says the ledger is replaced; the result says it was replaced.
    Undo last restore says the same. Import a normalized ZIP: no "Simulation scenarios" are listed; the
    commit confirmation and result say the ledger changed; undoing the import reports complete or partial.
  - Nothing on these screens calls real data simulated or empty. The fake flavor keeps its simulation labels
    and the `IOU-SIMULATION-EMPTY-*.txt` names.

## 7. Signed release APK (spec §2.4, §10)

- [x] After the release workflow, download the APK from the GitHub release and check its certificate:
  `apksigner verify --print-certs iou-vX.Y.Z.apk` shows `CN=IOU` and SHA-256
  `328199658371ad458470be6ec4970597e1763ac4b4921277161dbd79bdf7b1c5`.
- [ ] Use a separate fresh, empty disposable upgrade image. Install the previous release's APK and start it;
  if checking persisted ledger data, seed only synthetic test data. Then run
  `adb install -r iou-vX.Y.Z.apk`. The upgrade succeeds without uninstalling, and the app starts and shows
  the same test ledger. Keep debug and signed-release builds on separate images because they use different
  signing keys.

Result (2026-11-22, `v0.2.1`): the release APK shows `CN=IOU` and the SHA-256 above. The upgrade item was last run
for `v0.2.0` over `v0.1.0` (section 10); not run for `v0.2.1`.

## 8. Compose UI integration pass (PR #1 merge, branch `integrate/compose-ui`)

Run on 2026-11-06 on a disposable API 37 AVD (`IOU_Integration_Review`), realDebug, synthetic data only ("MergeTester").
Only items marked passed were observed; everything unchecked is still open.

Passed on the emulator (2026-11-06):

- [x] Real first-run setup shows no simulation notice; person and loan creation; force-stop and reopen keeps the data.
- [x] Backup with encryption on: `iou-backup.ioub` (1146 bytes, `IOUB` header). Backup with encryption off: `iou-backup.json` (4356 bytes,
  real JSON including identity), after the plaintext warning and acknowledgement.
- [x] Normalized export is a ZIP ("Exported 3 rows"); the report CSV is correct; the Format field offers CSV only with the XLSX note.
- [x] Restore, with a rotation between picking the file and confirming: balance restored and the outcome snackbar shown. Undo restore
  brought the earlier balance back.
- [x] App lock turned on with the device PIN. 1-minute timeout: returning after 10 s stayed unlocked, after 70 s showed the unlock screen.
  Immediately: every return locks. Returning from a document picker with Immediately locks, and the picker result is delivered after unlock.
- [x] Import preview of the app's own ZIP (3 duplicates); Back discards it. The draft survives rotation, and the form reopens blank after discard.
- [x] No crash entries in the log buffer during the pass.

Open items (not done, or only partly covered):

- [ ] App lock on, screen protection off: the Recents thumbnail shows content. This is by design: per spec §9, `FLAG_SECURE` follows
  screen protection only, never app lock. Confirm that with protection on the thumbnail is blank.
- [ ] Interrupted restore notice: kill the app during a pre-commit restore, relaunch and unlock; expect the single "The last restore was
  interrupted. Nothing changed." dialog (the ViewModel path is covered by JVM tests; the on-device path was not run).
- [ ] Nested group creation from a personal `NeedsGroup` keeps the parent form's draft when it returns.
- [ ] Biometric success, cancel and lockout paths on a device with a biometric sensor (the emulator had none; only the PIN path ran).
- [ ] Screenshots and Recents with screen protection on (refused and blank), including the first frame of a cold start.
- [ ] Fake-flavor walkthrough (simulation labels, demo data, canned import scenarios).
- [ ] Repeated open, cancel and save of the same form (new transaction, person, group): each visit starts blank and no draft survives a
  pop (route ViewModels are cleared when a route leaves the stack).
- [ ] Remaining validation matrix cases: expense split modes and FX choices on a device, settlement drafts, and an import round trip with a
  foreign identity, invalid rows and undo.
- [ ] Compose instrumentation suite (`androidTest`): carried over. This merge relies on JVM tests and the passes above; destination
  disposal, semantics, system pickers and lifecycle ordering still have no automated device test.
- Accepted limitation (not a check): the import screen can show the SAF document id instead of the file's display name.

## 9. Recurring expenses on the device (spec §5.7, AC 29 and 34)

Use a disposable emulator with the real debug build. Open Recurring expenses from the calendar icon in the app bar. See [M2 verification](m2-verification.md) for the latest integration results. An earlier worker smoke check was recorded on 2026-11-08 at `89a6d0d`: the app starts with no crash, the `RecurringPostWorker` job is registered, and its first run at enqueue gives no crash.

1. Create an auto-post schedule whose first date is today, close the app, open it again: the expense is posted once; reopening posts nothing more (AC 29).
2. Grant notifications. With the app closed, run the worker on demand: `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler io.github.codex07.iou <job id>` (the id is on the `#RecurringPostWorker#` line of `adb shell dumpsys jobscheduler`). WorkManager delays a forced run before the 6-hour period is up (logcat: "Delaying execution ... before schedule"), so move the emulator clock forward 6 hours first. An auto-post date that came due is posted and one "Recurring expenses posted" notification appears.
3. A confirm-first schedule that becomes due shows one "due" notification; running the worker again does not repeat it; posting the date in the app removes it.
4. Turn app lock on and repeat 2–3: neither notification names a person, an amount or a description. Then, with a detailed due and a detailed posted notification showing (lock off), switch app lock on in the app: the due notification is redrawn without its description or amount and makes no sound, and the posted one is removed.
5. Deny or revoke the notification permission and run the worker: the date is still posted, nothing is shown.
6. Tap a due notification with the app stopped, then repeat with it running. The aggregate notification opens Due expenses and a schedule notification focuses that schedule. With app lock enabled, verify that the financial screen appears only after authentication. Also exercise the first-save permission prompt once with Allow and once with Don't allow on separate disposable installs.
7. With two confirm-first schedules due (two due notifications and their group, which alerted once), run the worker twice more and bring the app to the foreground: no further sound or vibration, and the notifications stay as they were (AC 34). Then post every due occurrence of one of them in the app: its notification goes, and the other schedule's notification is still shown (silently redrawn, without the group).

- [ ] Result recorded: not run as a whole yet. Covered so far: the UI contributor's emulator run ([M2 verification](m2-verification.md)) saw a due notification open Due expenses (also behind app lock), and the first-save permission prompt with Don't allow; a 2026-11-22 emulator run showed the new notification icon. Items 2–7 (worker cadence, grouped notifications, lock redraws, permission changes) are still open.

## 10. M2 import, XLSX on the device and the upgrade (spec §7.3–7.5, §14 item 1, AC M2-15, 16, 25)

Use a disposable emulator instance (see the device rules in the local project notes) for the upgrade and the import, not
the owner's long-lived AVD.

- [ ] Install `v0.1.0` (the released APK), set up, add a person, a group with a removed member whose net is 0, and
  a few expenses; create an unencrypted backup and a normalized export. Install the `v0.2.0` candidate over it
  (`adb install -r`): the app starts, every balance and group net is unchanged, and every expense shows one payer
  (AC 25).
- [x] On the upgraded app, choose XLSX in Export data and save both shapes. Pull the files and open them in
  Excel and LibreOffice to check FastExcel output from Android (spec §14 item 1).
- [ ] Restore the `v0.1.0` backup and import the `v0.1.0` normalized export into a freshly set-up install (another
  identity): the identity prompt appears, nothing is invalid, every net matches (AC 16, 25). Undo the import: it
  reports nothing skipped and every net is 0.
- [ ] Export the upgraded ledger with a schedule (normalized CSV) and import it back into the same install: every
  row is a duplicate and nothing changes.

Result (2026-11-21, signed release APKs on the disposable API 37 emulator):

- Upgrade: the released `v0.1.0` has no UI (a placeholder screen; the Compose UI was merged after that tag), so no
  user data can exist in it and items 1 and 3 can't be run as written. `iou-v0.2.0.apk` installed over `v0.1.0`
  with `adb install -r` (same signing key, versionCode 4), opened the database `v0.1.0` had created with no crash,
  and showed setup. Moving data from schema 1 is covered by the Room migration tests on `app/schemas/`.
- XLSX: on the upgraded app, set up, added two people and a 60 EUR expense paid 30/30 by two payers, then saved
  both XLSX shapes through the document picker. The normalized workbook (11 sheets, `people` … `expense_payers`)
  and the report workbook (Summary, Report; "Tester 30.00; Alex 30.00") open in LibreOffice 26.8 headless and in
  desktop Excel (read-only, not saved) with the expected values.
- Still open: the round-trip duplicate import (item 4). The UI contributor's run
  ([M2 verification](m2-verification.md)) covered a normalized CSV import from another ledger with its identity
  prompt and group balance lines.
