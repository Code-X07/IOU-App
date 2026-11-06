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

- [ ] Build the release APK and list its permissions:

  ```bash
  ./gradlew assembleRealRelease
  "$ANDROID_HOME/build-tools/<latest>/aapt2" dump permissions app/build/outputs/apk/real/release/app-real-release-unsigned.apk
  ```

  Expected, and nothing else:
  - `INTERNET` (declared by the real flavor);
  - `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (WorkManager);
  - `io.github.codex07.iou.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature permission that
    `androidx.core` declares for the app's own receivers;
  - `USE_BIOMETRIC`, once the UI adds `androidx.biometric`.

  `FOREGROUND_SERVICE` must be absent; `src/real/AndroidManifest.xml` removes it. For anything new, find
  its source in `app/build/outputs/logs/manifest-merger-real-release-report.txt`. Remove it with
  `tools:node="remove"` if the app doesn't need it.
- [ ] In `app/build/intermediates/merged_manifest/realRelease/processRealReleaseMainManifest/AndroidManifest.xml`,
  the exported components are `MainActivity`, WorkManager's `SystemJobService` (`BIND_JOB_SERVICE`), and
  `DiagnosticsReceiver` and `ProfileInstallReceiver` (both `DUMP`). There is no `KdfTimingReceiver`; it is
  realDebug only.
- [ ] The application has `allowBackup="true"`, `fullBackupContent="@xml/backup_rules"`,
  `dataExtractionRules="@xml/data_extraction_rules"` and `usesCleartextTraffic="false"`.

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

- [ ] `curl "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=SEK%2CUSD"` answers 200 with JSON-number
  rates and the latest working day as `date`.
- [ ] `curl "https://api.frankfurter.dev/v1/2026-11-01?base=EUR&symbols=SEK"` (a Sunday) answers with
  `"date":"2026-10-30"`.
- [ ] A future date (`/v1/2099-01-01?base=EUR&symbols=SEK`) answers 404 `{"message":"not found"}`. The app
  treats any answer except 200, and any timeout, as unavailable (spec §4.5).

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
- [ ] XLSX export arrives in M2 (spec §7.3); check it then.
- [ ] On a real-flavor build (Settings > Backup & data management), with synthetic data only:
  - Create backup with encryption off: the warning and its acknowledgement are required; the picker
    suggests `iou-backup.json`; the result says the backup was saved. Pulled file starts with `{` and holds
    the ledger.
  - Create backup with encryption on: the picker suggests `iou-backup.ioub`; the pulled file starts with
    `IOUB`.
  - Export normalized CSV: the picker suggests `iou-export.zip`; the file is a ZIP with `manifest.json`.
    Export a report: `iou-export.csv`. The Format field offers CSV only, with a line that XLSX arrives later.
  - Restore a backup: the confirmation says the ledger is replaced; the result says it was replaced.
    Undo last restore says the same. Import a normalized ZIP: no "Simulation scenarios" are listed; the
    commit confirmation and result say the ledger changed; undoing the import reports complete or partial.
  - Nothing on these screens calls real data simulated or empty. The fake flavor keeps its simulation labels
    and the `IOU-SIMULATION-EMPTY-*.txt` names.

## 7. Signed release APK (spec §2.4, §10)

- [ ] After the release workflow, download the APK from the GitHub release and check its certificate:
  `apksigner verify --print-certs iou-vX.Y.Z.apk` shows `CN=IOU` and SHA-256
  `328199658371ad458470be6ec4970597e1763ac4b4921277161dbd79bdf7b1c5`.
- [ ] Use a separate fresh, empty disposable upgrade image. Install the previous release's APK and start it;
  if checking persisted ledger data, seed only synthetic test data. Then run
  `adb install -r iou-vX.Y.Z.apk`. The upgrade succeeds without uninstalling, and the app starts and shows
  the same test ledger. Keep debug and signed-release builds on separate images because they use different
  signing keys.
