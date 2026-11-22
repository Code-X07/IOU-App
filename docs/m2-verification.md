# M2 UI verification

Verified on 21 November 2026 on `feat/m2-ui`, starting from `142b0e5`.

## Automated checks

| Check | Fake debug | Real debug |
| --- | --- | --- |
| Unit tests | 696 passed | 696 passed |
| APK assembly | Passed | Passed |
| Android lint | Passed with 39 warnings and zero errors | Passed with 39 warnings and zero errors |

Ran the six Gradle tasks together on Windows with JDK 21 and the locally installed Android SDK.

```powershell
.\gradlew.bat :app:testFakeDebugUnitTest :app:testRealDebugUnitTest :app:assembleFakeDebug :app:assembleRealDebug :app:lintFakeDebug :app:lintRealDebug --offline --console=plain --continue
```

Post and Skip start with the oldest due occurrence. The skip-through picker offers the dates currently due for that schedule.

The suites cover schedule creation and editing with exact payers, anchored month-end previews, posting with an edited expense date, skip confirmation with a stale token, an archive starting a hold during review, recurring intent parsing and stack behavior, XLSX generation and readback, and forced import choices. The full suites also ran the existing people, groups, expenses, transfers, FX, security, backup, restore, and import lifecycle tests.

## Android emulator

Used a disposable API 37 emulator and both debug flavors.

| Area | Observed result |
| --- | --- |
| Fake recurring | A cold due intent opened the seeded Rent occurrence. Posted it and followed the expense's link back to the schedule. In a fresh fake process, an unknown schedule ID fell back to the due list and skipping Rent cleared the item. A warm due intent reached the running activity. Streaming appeared in the schedule list. Deleted and restored Rent from the schedule screens. |
| Real recurring and notifications | Created a schedule with two payers and saw the next date before saving. Android offered notifications on the first successful save. Denying permission kept the schedule saved, and editing it kept the two payers without offering permission again. After granting permission through ADB, the real app produced a due notification. Tapping it in the notification shade focused the corresponding schedule. The imported Streaming auto-post schedule showed the instruction to confirm older occurrences before auto-posting resumes. After archiving Flat, the Rent schedule appeared in the separate On hold list with Flat named as the reason. |
| App lock | Enabled app lock with the disposable emulator's Android PIN. A cold due intent showed the lock screen with no due list. After PIN authentication, the pending intent opened Due expenses. |
| Real XLSX | Saved both shapes through the Android document picker. Pulled the resulting workbooks and verified their ZIP integrity and worksheet names. The normalized workbook had the ledger tables, including `expense_payers` and `recurring_expenses`. The report workbook had Summary and Report. The automated backend workbook tests also verified report sheets for groups. |
| Real import | Opened a normalized CSV ZIP from another ledger. The identity prompt preceded the balance preview. Person balances, Flat (group), and Lisbon trip (group) showed before and after amounts. Committed the import, then opened a second fixture with forced-only Rent and mixed Streaming changes. The handled dates each appeared once under Always applied, and only the mixed row displayed Keep local. Discarded the second preview. |

## Before release

Status after the `v0.2.0` release: the signed packaging, the upgrade over `v0.1.0` and the desktop Excel and LibreOffice check of device-made workbooks were done (manual checklist sections 7 and 10). The section 9 worker and notification checks and the round-trip import are still open.

- Run the periodic worker and notification cadence checks in section 9 of the [manual checklist](manual-test-checklist.md), including grouped notifications, granting permission from the first-save prompt, and permission changes across worker passes.
- Run the released `v0.1.0` upgrade, backup, CSV import and undo checks in section 10 of the [manual checklist](manual-test-checklist.md).
- Open both Android-generated workbook shapes in desktop Excel and LibreOffice. Android file creation and workbook structure were verified on the emulator.
- Verify signed release packaging per the [manual checklist](manual-test-checklist.md).

## Files changed since `142b0e5`

| Change | Repository path |
| --- | --- |
| Modified | `android/app/src/main/AndroidManifest.xml` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/MainActivity.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/RecurringTriggers.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/api/Recurring.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ledger/engine/Recurrence.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/IOUApp.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/IouRoot.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/components/AdvancedActions.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/components/DashboardComponents.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/components/IouIcons.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/forms/LedgerErrorMessage.kt` |
| Added | `android/app/src/main/kotlin/io/github/codex07/iou/ui/forms/ScheduleFields.kt` |
| Added | `android/app/src/main/kotlin/io/github/codex07/iou/ui/navigation/RecurringNotificationRoute.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/ActivityScreen.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/BrowseComponents.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/DataManagementScreen.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/EntryFormScreen.kt` |
| Added | `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/RecurringScreens.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/settings/DataModels.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/AdvancedViewModel.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/DataManagementViewModel.kt` |
| Modified | `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/EntryFormViewModel.kt` |
| Added | `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/RecurringViewModels.kt` |
| Added | `android/app/src/test/kotlin/io/github/codex07/iou/ui/navigation/RecurringNotificationRouteTest.kt` |
| Modified | `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/AdvancedManagementTest.kt` |
| Modified | `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/DataManagementTest.kt` |
| Modified | `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/FinancialFormsTest.kt` |
| Added | `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/RecurringFormsTest.kt` |
| Modified | `docs/m2-ui-contract-changes.md` |
| Modified | `docs/manual-test-checklist.md` |
| Added | `docs/m2-verification.md` |
