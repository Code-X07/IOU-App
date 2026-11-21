# IOU App implementation review

Reviewed on 21 November 2026 at `e7656fa` on `main`, after the v0.2.0 release. The live GitHub branch matched the local checkout.

| Tier | Findings |
| --- | ---: |
| Critical | 0 |
| Important | 0 |
| Medium | 2 |
| Low | 1 |
| Very low | 0 |

## Critical

No findings.

## Important

No findings.

## Medium

### F1 — Editing a converted entry silently reuses its old rate

Edit the transaction date or paid amount on a converted expense or transfer with a saved reference or manual rate. The edit form starts in `SAVED` mode and stays there after the change. Save passes `KeepExisting`, so the ledger applies the old rate automatically. The user can switch to `AUTO` manually. Spec §4.5 calls for proposing a fresh quote after a same-pair date or amount change, with an explicit option to keep the previous rate. A date correction can therefore leave the entry and its group balances valued at the wrong date's rate.

Request a fresh quote when the date or paid amount changes on a rate-based edit, and keep the existing rate available as an explicit choice. Preserve the existing bank-actual behavior, including keeping the recorded charge across date-only edits and requiring a new FX choice when the paid amount changes.

Evidence in [edit draft field mapping](../../android/app/src/main/kotlin/io/github/codex07/iou/ui/forms/EntryInput.kt#L79), [date editing and FX controls](../../android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/EntryFormScreen.kt#L209), [FX choice preparation](../../android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/EntryFormViewModel.kt#L262), [saved-rate application](../../android/app/src/main/kotlin/io/github/codex07/iou/ledger/ops/EntryOps.kt#L251) and [MVP spec §4.5](../superpowers/specs/2026-11-02-iou-mvp-design.md#L463).

### F2 — Backup creation can mark an unrestorable file as a successful backup

A large ledger, including its revision history, can produce a backup larger than the restore ceiling of 64 MiB on disk or 256 MiB after decompression. Backup creation writes the file, updates `lastManualBackupAt` and returns success without checking either ceiling. Restore rejects the resulting file with `LimitsExceeded`. The user can learn the backup is unusable only when they need it, and the successful timestamp suppresses the manual backup reminder.

Check the serialized and final file sizes against the restore limits before writing to the selected document, and leave the reminder unchanged on rejection. A test with small injected limits can cover the boundary without building a large test ledger.

Evidence in [backup creation and restore](../../android/app/src/main/kotlin/io/github/codex07/iou/data/RealBackupApi.kt#L44), [format encoding and read checks](../../android/app/src/main/kotlin/io/github/codex07/iou/data/BackupFormat.kt#L43), [read-side limit test](../../android/app/src/test/kotlin/io/github/codex07/iou/data/BackupFormatTest.kt#L125) and [MVP spec §7.1](../superpowers/specs/2026-11-02-iou-mvp-design.md#L675).

## Low

### F3 — A grouped schedule notification can open the unfiltered due list

When two schedules are due, a child notification can still be titled for one schedule but open all due expenses. The notifier adds the schedule ID only if exactly one schedule is due overall. A child drawn earlier when it was the sole reminder can retain its focused route until it is redrawn. The user can therefore get different destinations from two schedule-specific notifications in the same group.

M2 Part 4's notifier example matches the implementation. Its UI handoff and the manual checklist say a notification about one schedule should focus that schedule. Pass the schedule ID on every schedule child and keep the group summary aggregate.

Evidence in the [notification adapter](../../android/app/src/main/kotlin/io/github/codex07/iou/data/AndroidRecurringNotices.kt#L60), [due-list filtering](../../android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/RecurringScreens.kt#L125), [M2 Part 4 handoff](../superpowers/plans/2026-11-07-m2-04-recurring-app.md#L1769), [M2 Part 4 notifier example](../superpowers/plans/2026-11-07-m2-04-recurring-app.md#L2175) and [manual checklist §9](../manual-test-checklist.md#L234).

## Very low

No findings.

## Resolution

| Finding | Status | Change |
| --- | --- | --- |
| F1 | Fixed | On the owner's go, in the friend's entry form. `EntryFormViewModel.change` switches a `SAVED` rate-based snapshot (lookup or manual) to `AUTO` when the date or paid amount changes, so a fresh quote is proposed; choosing "Keep stored snapshot" again keeps the old rate. Bank-actual snapshots are untouched (date-only edits keep them; an amount change still needs a new FX choice). The AUTO hint names the keep option. Tests: `FinancialFormsTest` "date or amount edits on a stored rate propose a fresh quote and keeping the old rate is explicit" and "bank actual date only edits keep the stored snapshot". |
| F2 | Fixed | `BackupFormat.encode` refuses JSON over the decompressed limit and a file over the file limit with `LimitsExceeded`; `RealBackupApi.createBackup` uses it, so nothing is written and `lastManualBackupAt` stays. `BackupApi` and spec §7.1 say so. Test: `RealBackupTest` "a backup the restore limits would refuse is not written and keeps the reminder" (plain over the file limit, encrypted over the JSON limit). |
| F3 | Fixed | `AndroidRecurringNotices` puts `iou.schedule` on every schedule notification; only the group summary opens the whole due list. Spec §5.7 updated. No JVM test: the adapter calls Android's `NotificationManagerCompat` and the project has no Robolectric; checklist §9 item 6 covers it on the device. |

## Spec and plan compliance

Used the current MVP spec, M0 and M1 milestone context, the five M2 plan parts, and the M2 UI design and plan. Treated the current specification and recorded decisions as the baseline where earlier milestones differed.

| Area | Implementation and coverage reviewed |
| --- | --- |
| Ledger and persistence | Money, FX, split and payer rules, contribution-based balances, group settlement, write-through revisions, schema 1 to 2 migration and snapshot upcasting. Existing engine, Room and API tests cover these paths. |
| Recurring expenses | Date anchoring, due order, holds and resumption, auto-posting, WorkManager triggers, and the notification planner and Android adapter. Existing runner and API tests exercise posting and races. Android delivery still has open checklist items. |
| Data management | Backup and restore, normalized CSV preview and merge, import undo and ownership cascades, XLSX output, and fake/real API wiring. Existing format and contract tests cover round trips and rules. The signed upgrade and desktop spreadsheet results are recorded in checklist §10. |
| Compose integration | Multiple payers, entry creation and edits, FX choices, recurring creation and occurrence review, group settlements, and import choices. Existing ViewModel tests and the M2 verification notes cover the principal flows. |

[MVP spec](../superpowers/specs/2026-11-02-iou-mvp-design.md) · [M2 plan index](../superpowers/plans/2026-11-07-m2-00-index.md) · [M2 UI design](../superpowers/specs/2026-11-21-multi-payer-ui-design.md) · [M2 UI plan](../superpowers/plans/2026-11-21-multi-payer-ui.md) · [M2 verification](../m2-verification.md) · [Manual checklist](../manual-test-checklist.md)

## Code quality and edge cases

Two documented runner follow-ups remain relevant. A large forward device-date change can post a long series of future occurrences in one pass because the loop uses the advanced date as its stopping point. Unexpected write or notification exceptions in requested passes are swallowed, which makes failures hard to diagnose. Both were already recorded at onboarding and are outside the new finding count. The owner has also accepted the case where notifications are disabled and re-enabled between runner passes.

[Recurring runner](../../android/app/src/main/kotlin/io/github/codex07/iou/store/RecurringRunner.kt#L115) · [Notification limitation in MVP spec §5.7](../superpowers/specs/2026-11-02-iou-mvp-design.md#L600)

## Obvious security issues

No obvious security defect was established in the source pass. The review covered app-lock gating and notification redaction, screen protection, backup encryption defaults and Android backup exclusions, merged permissions and exported components, cleartext policy, and signing secret references. It did not include dependency auditing or device-based security testing.

[Security spec](../superpowers/specs/2026-11-02-iou-mvp-design.md#L809) · [Main manifest](../../android/app/src/main/AndroidManifest.xml) · [Real manifest](../../android/app/src/real/AndroidManifest.xml) · [Cloud and device transfer rules](../../android/app/src/main/res/xml/data_extraction_rules.xml) · [Release workflow](../../.github/workflows/release.yml)

## Verification

- GitHub CI succeeded on the exact reviewed commit. The workflow runs unit tests, lint, and debug assembly in both flavors. [CI run](https://github.com/Code-X07/IOU-App/actions/runs/44940854619)
- The local combined run and single-worker retry both stopped at the fake-flavor compile JAR with a Windows file-lock error. The real-only retry encountered the same error on the real-flavor compile JAR. The review relies on exact-commit CI for test results.
- Findings were traced in source. No new device tests were run. Outstanding device verification includes the periodic worker and grouped notification cadence, biometric and screen-protection behavior, and the duplicate-import round trip. [Manual checklist §§8–10](../manual-test-checklist.md#L190)
