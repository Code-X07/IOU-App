# M2: what changes for the UI

Two new features are coming in M2: expenses paid by several people, and recurring expenses. Nothing changes in the code yet; this is a heads-up so we agree before the core work starts. Full details: spec §4.4, §5.7, §6.2 (`docs/superpowers/specs/2026-11-02-iou-mvp-design.md`).

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

The fake will get a two-payer group expense, a monthly confirm-first schedule with one date due, and an auto-post schedule.
