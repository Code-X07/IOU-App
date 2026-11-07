# M2: what changes for the UI

Two new features are coming in M2: expenses paid by several people, and recurring expenses. Nothing changes in the code yet; this is a heads-up so we agree before the core work starts. Full details: spec §4.4, §5.7, §6.2 (`docs/superpowers/specs/2026-11-02-iou-mvp-design.md`).

## Multiple payers

- `ExpenseDraft.payer` goes away. Instead: `payerSplit` (`EQUAL` or `EXACT`) and `payers: List<SplitInput>`, the same shape as `participants`. A single payer = one entry, no value.
- EXACT payer amounts must each be > 0 and add up to the total.
- Read models: `paidBy` becomes `payers: List<PayerView(person, paid)>`. `previewSplit` also returns the resolved payers. Flows stay as they are.
- New errors: `NoPayers`, `PaidRemaining(diff)`, `DuplicatePayer(person)`; `NegativeInput` / `MissingInput` / `InvalidAmount` can now be about payers too.
- Screens affected: the expense form, and everywhere that shows "paid by" (activity, entry detail, group expenses).
- Existing expenses become "one payer, full amount"; no balance changes.

## Recurring expenses

- New `RecurringApi`: list schedules (filter for "on hold"), schedule detail, save a `RecurringDraft` (expense template + repeat every N weeks/months/years, start, optional end, auto-post on/off), delete/restore, the due list, `occurrenceDraft`, `postOccurrence`, `skipOccurrences`.
- Each schedule either posts by itself or shows due dates to confirm. Due dates are posted or skipped oldest first; posting an out-of-order date gives `OccurrenceNotDue(next)`.
- A schedule whose group or one of its people is archived is "on hold". Please show held schedules on a separate screen, not in the main schedule list.
- Posted expenses are normal expenses with `recurringId` / `recurringOn`, so you can mark them and link to their schedule.
- History and revert work through the existing `revertTo` / `draftFromVersion`.

## Notifications (real build only)

- The core posts the notifications ("due" and "posted") and runs a background job.
- UI work: ask for the `POST_NOTIFICATIONS` permission the first time a schedule is saved (Android 13+), and handle the `iou.open` extra on `MainActivity` (value `"due"`, optionally a schedule ID) by opening the due list.
- The fake build has no background job and no notifications; due items still appear in the fake.

## Fake data

The fake will get a two-payer group expense, a monthly confirm-first schedule with one date due, and an auto-post schedule.
