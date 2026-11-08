# M2: what changes for the UI

M2 adds expenses paid by several people, recurring expenses, group and schedule import, and XLSX export. **All of it is live on `main` now** (both flavors; notifications in the real build only). The core side is done and reviewed; `v0.2.0` ships once the screens below are in, followed by a bug-fix round. Full details: spec §4.4, §5.7, §6.2, §7 (`docs/superpowers/specs/2026-11-02-iou-mvp-design.md`) and `docs/ui-contributor-guide.md`.

## UI to-do before v0.2.0

- [ ] **Payers on the expense form:** move the form to `ExpenseDraft.payers` / `payerSplit` (EQUAL or EXACT amounts) and show every payer wherever "paid by" appears (activity, entry detail, group expenses) from `ActivityItem.payers`. See "Multiple payers" below.
- [ ] **Schedule screens:** schedule list, schedule detail and edit form (with the next date shown before saving), a separate "on hold" list, and the due list with post / skip and the "confirm these to resume auto-posting" line. Inject `RecurringApi` (also `Apis.recurring`). See "Recurring expenses" below.
- [ ] **Notification permission:** ask for `POST_NOTIFICATIONS` the first time a schedule is saved (Android 13+). The real build already declares it in its manifest.
- [ ] **Notification taps:** in `MainActivity`, read the intent extra `iou.open`; for `"due"` open the due list, and when `iou.schedule` is present, that schedule. Today the extras are ignored and the app just opens.
- [ ] **XLSX export:** allow XLSX in the export Format field. Three places still block or deny it: `ui/settings/DataModels.kt` (rejects XLSX with `InvalidField("format")`, and `DataManagementTest` expects that), `ui/forms/LedgerErrorMessage.kt` line 27 ("XLSX arrives in a later version") and `ui/screens/DataManagementScreen.kt` line 163 (same text).
- [ ] **Import preview:** show schedule rows with `forcedChanges` like the "deleted with" rows (nothing to choose), and the "<group> (group)" balance lines. See "Import and export" below.

## Multiple payers

- `ExpenseDraft` has `payerSplit` (`EQUAL` or `EXACT`) and `payers: List<SplitInput>`, the same shape as `participants`. A single payer = one entry, no value. Until the form moves over, the old constructor `ExpenseDraft(payer, description, …)` still works and `draft.payer` (deprecated) returns the first payer. Editing a several-payer expense through `draft.payer` saves one payer, so the form should switch to `payers` before such expenses appear in real data (the fake already has one: "Museum tickets" in the Lisbon trip).
- EXACT payer amounts must each be > 0 and add up to the total.
- Read models: `ActivityItem.payers: List<PayerView(person, paid, inputValue)>` next to `paidBy`, which stays as the first payer. `previewSplit` also returns the resolved payers, and when editing the stored ones and `payersRecomputed`. Flows stay as they are.
- New errors: `NoPayers`, `PaidRemaining(diff)`, `DuplicatePayer(person)`; `NegativeInput` / `MissingInput` / `InvalidAmount` can now be about payers too.
- Screens affected: the expense form, and everywhere that shows "paid by" (activity, entry detail, group expenses).
- Existing expenses become "one payer, full amount"; no balance changes.

## Recurring expenses

- New `RecurringApi`: list schedules (filter for "on hold"), schedule detail, save a `RecurringDraft` (expense template + repeat every N weeks/months/years, start, optional end, auto-post on/off), delete/restore, the due list, `occurrenceDraft`, `postOccurrence`, `skipOccurrences`.
- Each schedule either posts by itself or shows due dates to confirm. Due dates are posted or skipped oldest first; posting an out-of-order date gives `OccurrenceNotDue(next)`.
- Auto-post never posts old dates by itself: any change to a schedule (creating, editing, restoring, ending a hold, importing) makes every date before that day wait in the due list, and auto-post for that schedule pauses until they are posted or skipped. Worth a line in the due list ("confirm these to resume auto-posting").
- Changing a schedule's pattern skips nothing: the next date is the first one of the new pattern after the last handled date. The schedule read model gives the next occurrence; showing it on the edit screen before saving avoids surprises.
- A schedule whose group or one of its people is archived is "on hold". Please show held schedules on a separate screen, not in the main schedule list. A hold also ends when an archived person is merged into an active one (or an active alias is unmerged from an archived person); the schedule then gets a "Resumed" history entry and its old dates wait in the due list like after an unarchive.
- Posted expenses are normal expenses with `recurringId` / `recurringOn`, so you can mark them and link to their schedule.
- History and revert work through the existing `revertTo` / `draftFromVersion`.

## Notifications (real build only)

- The core posts the notifications ("due" and "posted") and runs a background job.
- UI work: ask for the `POST_NOTIFICATIONS` permission the first time a schedule is saved (Android 13+), and handle the `iou.open` extra on `MainActivity` (value `"due"`; `iou.schedule` carries the schedule ID when the notification is about one schedule) by opening the due list.
- The fake build has no background job and no notifications; due items still appear in the fake.

## Import and export

- Group rows now import (groups, memberships, group entries), with the same "deleted with / restored with <owner>" rows as people and their handles; the owner is now a group or a person.
- Schedules import too. A schedule whose copy in the file is further along moves forward by itself: its row has `forcedChanges` (also in `changes`) and nothing to choose. Please show such rows like the "deleted with" rows.
- `balanceChanges` now also lists my net per group, labelled "<group> (group)", after the people.
- XLSX export works for both shapes: the Format field can offer it (and drop the "XLSX arrives later" line).

## Fake data

The fake has a two-payer group expense ("Museum tickets" in the Lisbon trip), a monthly confirm-first schedule ("Rent") with one date due, and an auto-post schedule ("Streaming").
